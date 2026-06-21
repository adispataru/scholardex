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
    private static final int CITER_BATCH = 500;
    private static final int BACKBONE_SAVE_BATCH = 1000;

    private final OpenAlexImportService openAlexImportService;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ObjectMapper objectMapper;

    public record BulkImportResult(int worksImported, int citersImported,
                                   int referencedInstitutions, int backboneInstitutions) {
    }

    /**
     * Run the full bulk import. Any of the three inputs may be {@code null}/absent — that part is skipped.
     */
    public BulkImportResult importAll(Path worksFile, Path citersFile, Path institutionsDir,
                                      String batchId, String correlationId) throws IOException {
        Set<String> referenced = new HashSet<>();
        int works = importWorksFile(worksFile, referenced, batchId, correlationId);
        int citers = importCitersFile(citersFile, referenced, batchId, correlationId);
        int backbone = importInstitutionBackbone(institutionsDir, referenced, batchId, correlationId);
        log.info("OpenAlex bulk import complete: works={} citers={} referencedInstitutions={} backbone={}",
                works, citers, referenced.size(), backbone);
        return new BulkImportResult(works, citers, referenced.size(), backbone);
    }

    // ── Works (full) ────────────────────────────────────────────────────────

    private int importWorksFile(Path worksFile, Set<String> referenced, String batchId, String correlationId)
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

    // ── Citers (bare neighbors) ───────────────────────────────────────────────

    private int importCitersFile(Path citersFile, Set<String> referenced, String batchId, String correlationId)
            throws IOException {
        if (citersFile == null || !Files.isRegularFile(citersFile)) {
            log.warn("OpenAlex bulk import: citers file missing, skipping ({})", citersFile);
            return 0;
        }
        int count = 0;
        List<OpenAlexWorksResponse.OpenAlexWork> batch = new ArrayList<>(CITER_BATCH);
        try (BufferedReader reader = Files.newBufferedReader(citersFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                OpenAlexWorksResponse.OpenAlexWork work = objectMapper.readValue(line, OpenAlexWorksResponse.OpenAlexWork.class);
                collectInstitutionIds(work, referenced);
                batch.add(work);
                if (batch.size() >= CITER_BATCH) {
                    count += openAlexImportService.upsertNeighborWorks(batch, batchId, correlationId).size();
                    batch.clear();
                    if (count % 10_000 == 0) {
                        log.info("OpenAlex bulk import: {} citers processed...", count);
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            count += openAlexImportService.upsertNeighborWorks(batch, batchId, correlationId).size();
        }
        log.info("OpenAlex bulk import: {} citers processed from {}", count, citersFile.getFileName());
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
        if (!buffer.isEmpty()) {
            affiliationFactRepository.saveAll(buffer);
            count += buffer.size();
        }
        log.info("OpenAlex bulk import: {} backbone affiliations derived ({} referenced ids, {} snapshot files)",
                count, referenced.size(), gzFiles.size());
        return count;
    }

    /**
     * Map an OpenAlex institution snapshot record to a backbone {@link ScholardexAffiliationFact}. ROR-keyed
     * canonical id ({@code saff_<hash(ror)>}); records without a ROR are skipped (ROR is the backbone key).
     */
    ScholardexAffiliationFact toBackboneFact(OpenAlexInstitutionRecord rec, String strippedInstitutionId,
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
        return "saff_" + CanonicalizationSupport.shortHash("ror|" + CanonicalizationSupport.normalizeToken(strippedRor));
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
