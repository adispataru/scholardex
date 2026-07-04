package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexInstitutionRecord;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * H73 slice 1 — file-driven bulk ingestion of the locally-held OpenAlex UVT neighborhood and the ROR
 * affiliation backbone, from {@code data/openalex/}. One coherent flow:
 *
 * <ol>
 *   <li><b>Works</b> — stream {@code uvt_works.jsonl} (full upsert into {@code openalex.publication_facts}
 *       with H63 corresponding/positional backfill) and {@code uvt_citing_works.jsonl} (bare neighbor
 *       upsert, no authorships — avoids the ~295k foreign-author explosion). While streaming, collect the
 *       distinct OpenAlex institution ids referenced by either file's authorships.</li>
 *   <li><b>Institutions</b> — stream {@code institutions/*.gz}, keep only the referenced ids, and derive
 *       backbone {@link ScholardexAffiliationFact}s (ROR-keyed, multilingual aliases, geo). This is the
 *       affiliation spine Scopus afids resolve into (slice 2).</li>
 * </ol>
 *
 * Records are large — everything is streamed line-by-line, never slurped. Idempotent: works upsert by id;
 * backbone records are ROR-keyed ({@code saff_<hash(ror)>}) so a re-run overwrites in place.
 */
@Service
@RequiredArgsConstructor
public class OpenAlexBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexBulkImportService.class);
    private static final String SOURCE_OPENALEX = "OPENALEX";
    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";
    private static final String ROR_ID_PREFIX = "https://ror.org/";
    private static final String BUILDER_VERSION = "openalex-bulk-import-v1";
    private static final int BACKBONE_SAVE_BATCH = 1000;

    private final OpenAlexImportService openAlexImportService;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    // H75 S1.0: persist referenced institutions as a source fact so the V2 engine derives the ROR backbone from
    // them (rather than the importer deriving it during ingest). Additive — V1's backbone write below is unchanged.
    private final ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository institutionFactRepository;
    // H80: fold per-venue APC derivation into the works/citers stream so a rebuild produces openalex.source_facts
    // in-DAG (before the stage-4 forum-membership projection reads them) — no separate 2.6 GB pass.
    private final ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexSourceFactRepository sourceFactRepository;
    private final ObjectMapper objectMapper;

    public record BulkImportResult(int worksImported, int citersImported,
                                   int referencedInstitutions, int backboneInstitutions,
                                   int apcSources, int apcFeeJournals) {
    }

    /**
     * Run the full bulk import. Any of the three inputs may be {@code null}/absent — that part is skipped.
     *
     * <p>H80: while streaming works + citers, also aggregates per-venue APC into {@code openalex.source_facts}
     * (fed to the fee-journal forum-membership projection) — a single pass, no separate scan of the dumps.</p>
     */
    public BulkImportResult importAll(Path worksFile, Path citersFile, Path institutionsDir,
                                      String batchId, String correlationId) throws IOException {
        Set<String> referenced = new HashSet<>();
        OpenAlexSourceApcAggregator sourceApc = new OpenAlexSourceApcAggregator();
        int works = importWorksFile(worksFile, referenced, sourceApc, batchId, correlationId);
        int citers = importCitersFile(citersFile, referenced, sourceApc, batchId, correlationId);
        int backbone = importInstitutionBackbone(institutionsDir, referenced, batchId, correlationId);
        int apcFee = persistSourceApc(sourceApc, batchId, correlationId);
        log.info("OpenAlex bulk import complete: works={} citers={} referencedInstitutions={} backbone={} "
                        + "apcSources={} apcFeeJournals={}",
                works, citers, referenced.size(), backbone, sourceApc.sourceCount(), apcFee);
        return new BulkImportResult(works, citers, referenced.size(), backbone, sourceApc.sourceCount(), apcFee);
    }

    /** H80: materialize + upsert the per-venue APC facts collected during the works/citers stream. Returns fee-journal count. */
    private int persistSourceApc(OpenAlexSourceApcAggregator sourceApc, String batchId, String correlationId) {
        List<ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact> facts =
                sourceApc.toFacts(batchId, correlationId, java.time.Instant.now());
        int fee = 0;
        List<ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact> buffer = new ArrayList<>(BACKBONE_SAVE_BATCH);
        for (var fact : facts) {
            buffer.add(fact);
            if (fact.isFeeJournal()) {
                fee++;
            }
            if (buffer.size() >= BACKBONE_SAVE_BATCH) {
                sourceFactRepository.saveAll(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            sourceFactRepository.saveAll(buffer);
        }
        return fee;
    }

    // ── Works (full) ────────────────────────────────────────────────────────

    private int importWorksFile(Path worksFile, Set<String> referenced, OpenAlexSourceApcAggregator sourceApc,
                                String batchId, String correlationId)
            throws IOException {
        if (worksFile == null || !Files.isRegularFile(worksFile)) {
            log.warn("OpenAlex bulk import: works file missing, skipping ({})", worksFile);
            return 0;
        }
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(worksFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                OpenAlexWorksResponse.OpenAlexWork work = objectMapper.readValue(line, OpenAlexWorksResponse.OpenAlexWork.class);
                collectInstitutionIds(work, referenced);
                sourceApc.observe(work);
                String id = openAlexImportService.importFullWork(work, batchId, correlationId);
                if (id != null) {
                    count++;
                }
                if (count % 2000 == 0 && count > 0) {
                    log.info("OpenAlex bulk import: {} full works upserted...", count);
                }
            }
        }
        log.info("OpenAlex bulk import: {} full works upserted from {}", count, worksFile.getFileName());
        return count;
    }

    // ── Citers (full — H73 slice 3: store authorships so pub→author→affiliation edges build) ──

    private int importCitersFile(Path citersFile, Set<String> referenced, OpenAlexSourceApcAggregator sourceApc,
                                 String batchId, String correlationId)
            throws IOException {
        if (citersFile == null || !Files.isRegularFile(citersFile)) {
            log.warn("OpenAlex bulk import: citers file missing, skipping ({})", citersFile);
            return 0;
        }
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(citersFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                OpenAlexWorksResponse.OpenAlexWork work = objectMapper.readValue(line, OpenAlexWorksResponse.OpenAlexWork.class);
                collectInstitutionIds(work, referenced);
                sourceApc.observe(work);
                // H73 slice 3: citers are imported FULL (with authorships) so their authors/affiliations
                // canonicalize and the pub→author→affiliation edges build — not bare neighbors anymore.
                if (openAlexImportService.importFullWork(work, batchId, correlationId) != null) {
                    count++;
                }
                if (count % 10_000 == 0 && count > 0) {
                    log.info("OpenAlex bulk import: {} citers processed (full)...", count);
                }
            }
        }
        log.info("OpenAlex bulk import: {} citers processed (full) from {}", count, citersFile.getFileName());
        return count;
    }

    private void collectInstitutionIds(OpenAlexWorksResponse.OpenAlexWork work, Set<String> referenced) {
        if (work.getAuthorships() == null) {
            return;
        }
        for (OpenAlexWorksResponse.Authorship a : work.getAuthorships()) {
            if (a == null || a.getInstitutions() == null) {
                continue;
            }
            for (OpenAlexWorksResponse.Institution i : a.getInstitutions()) {
                String iid = strip(i == null ? null : i.getId(), OPENALEX_ID_PREFIX);
                if (iid != null && !iid.isBlank()) {
                    referenced.add(iid);
                }
            }
        }
    }

    // ── Institution backbone ──────────────────────────────────────────────────

    int importInstitutionBackbone(Path institutionsDir, Set<String> referenced, String batchId, String correlationId)
            throws IOException {
        if (institutionsDir == null || !Files.isDirectory(institutionsDir)) {
            log.warn("OpenAlex bulk import: institutions dir missing, skipping ({})", institutionsDir);
            return 0;
        }
        if (referenced.isEmpty()) {
            log.warn("OpenAlex bulk import: no referenced institution ids collected — backbone empty");
            return 0;
        }
        int count = 0;
        List<ScholardexAffiliationFact> buffer = new ArrayList<>(BACKBONE_SAVE_BATCH);
        // H75 S1.0: persist every referenced institution as a source fact (regardless of ROR — the V2 derivation
        // decides, mirroring toBackboneFact's null-on-no-ROR), in addition to the backbone below.
        List<ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact> instBuffer =
                new ArrayList<>(BACKBONE_SAVE_BATCH);
        List<Path> gzFiles;
        try (Stream<Path> files = Files.list(institutionsDir)) {
            gzFiles = files.filter(p -> p.getFileName().toString().endsWith(".gz")).sorted().toList();
        }
        for (Path gz : gzFiles) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    OpenAlexInstitutionRecord rec = objectMapper.readValue(line, OpenAlexInstitutionRecord.class);
                    String iid = strip(rec.getId(), OPENALEX_ID_PREFIX);
                    if (iid == null || !referenced.contains(iid)) {
                        continue;
                    }
                    instBuffer.add(toInstitutionFact(rec, iid, batchId, correlationId));
                    if (instBuffer.size() >= BACKBONE_SAVE_BATCH) {
                        institutionFactRepository.saveAll(instBuffer);
                        instBuffer.clear();
                    }
                    ScholardexAffiliationFact fact = toBackboneFact(rec, iid, batchId, correlationId);
                    if (fact == null) {
                        continue;
                    }
                    buffer.add(fact);
                    if (buffer.size() >= BACKBONE_SAVE_BATCH) {
                        affiliationFactRepository.saveAll(buffer);
                        count += buffer.size();
                        buffer.clear();
                    }
                }
            }
        }
        if (!instBuffer.isEmpty()) {
            institutionFactRepository.saveAll(instBuffer);
        }
        if (!buffer.isEmpty()) {
            affiliationFactRepository.saveAll(buffer);
            count += buffer.size();
        }
        log.info("OpenAlex bulk import: {} backbone affiliations derived ({} referenced ids, {} snapshot files)",
                count, referenced.size(), gzFiles.size());
        return count;
    }

    /**
     * H75 S1.0 — map an OpenAlex institution snapshot record to the {@code openalex.institution_facts} source fact,
     * storing the <b>raw</b> mapping inputs (stripped id/ROR, display name + aliases/acronyms, geo, country code) so
     * the V2 affiliation builder re-derives the backbone with the same logic as {@link #toBackboneFact}.
     */
    public ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact toInstitutionFact(
            OpenAlexInstitutionRecord rec, String strippedInstitutionId, String batchId, String correlationId) {
        ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact fact =
                new ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact();
        fact.setId(strippedInstitutionId);
        fact.setRor(strip(rec.getRor(), ROR_ID_PREFIX));
        fact.setDisplayName(rec.getDisplay_name());
        if (rec.getDisplay_name_alternatives() != null) {
            fact.setDisplayNameAlternatives(new ArrayList<>(rec.getDisplay_name_alternatives()));
        }
        if (rec.getDisplay_name_acronyms() != null) {
            fact.setDisplayNameAcronyms(new ArrayList<>(rec.getDisplay_name_acronyms()));
        }
        fact.setCountryCode(rec.getCountry_code());
        OpenAlexInstitutionRecord.Geo geo = rec.getGeo();
        fact.setGeoCity(geo == null ? null : geo.getCity());
        fact.setGeoCountry(geo == null ? null : geo.getCountry());
        Instant now = Instant.now();
        fact.setCreatedAt(now);
        fact.setUpdatedAt(now);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(strippedInstitutionId);
        fact.setSourceBatchId(batchId);
        fact.setSourceCorrelationId(correlationId);
        fact.setBuilderVersion(BUILDER_VERSION);
        return fact;
    }

    /**
     * Map an OpenAlex institution snapshot record to a backbone {@link ScholardexAffiliationFact}. ROR-keyed
     * canonical id ({@code saff_<hash(ror)>}); records without a ROR are skipped (ROR is the backbone key).
     */
    public ScholardexAffiliationFact toBackboneFact(OpenAlexInstitutionRecord rec, String strippedInstitutionId,
                                             String batchId, String correlationId) {
        String ror = strip(rec.getRor(), ROR_ID_PREFIX);
        if (ror == null || ror.isBlank()) {
            return null;
        }
        ScholardexAffiliationFact fact = new ScholardexAffiliationFact();
        fact.setId(buildRorBackboneId(ror));
        CanonicalizationSupport.addUnique(fact.getRorIds(), ror);
        fact.setName(rec.getDisplay_name());
        fact.setNameNormalized(CanonicalizationSupport.normalizeName(rec.getDisplay_name()));
        addAliases(fact, rec.getDisplay_name_alternatives());
        addAliases(fact, rec.getDisplay_name_acronyms());
        OpenAlexInstitutionRecord.Geo geo = rec.getGeo();
        fact.setCity(geo == null ? null : geo.getCity());
        // Prefer the full country name (matches the Scopus affiliation `country` convention); fall back to ISO code.
        String country = geo != null && geo.getCountry() != null && !geo.getCountry().isBlank()
                ? geo.getCountry()
                : rec.getCountry_code();
        fact.setCountry(country);
        Instant now = Instant.now();
        fact.setCreatedAt(now);
        fact.setUpdatedAt(now);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(strippedInstitutionId);
        fact.setSourceEventId(strippedInstitutionId);
        fact.setSourceBatchId(batchId);
        fact.setSourceCorrelationId(correlationId);
        fact.setBuilderVersion(BUILDER_VERSION);
        return fact;
    }

    /** ROR-derived canonical affiliation id — same namespace/scheme as the Scopus afid-derived id. */
    public static String buildRorBackboneId(String strippedRor) {
        return CanonicalizationSupport.buildRorBackboneAffiliationId(strippedRor);
    }

    private static void addAliases(ScholardexAffiliationFact fact, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                CanonicalizationSupport.addUnique(fact.getAliases(), v.trim());
            }
        }
    }

    private static String strip(String value, String prefix) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }
}
