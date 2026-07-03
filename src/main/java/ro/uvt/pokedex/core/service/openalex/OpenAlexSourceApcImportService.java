package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexSourceFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * H79 — derives per-venue APC facts from the OpenAlex works dumps we already downloaded
 * ({@code data/openalex/uvt_works.jsonl} + {@code uvt_citing_works.jsonl}), entirely offline.
 *
 * <p>Each work embeds its {@code primary_location.source} ({@code id}, {@code issn}, {@code is_oa},
 * {@code is_in_doaj}) plus a work-level {@code apc_list} (the venue's advertised APC, USD-normalized).
 * Streaming both dumps and aggregating by source id yields one {@link OpenAlexSourceFact} per venue —
 * the fee-journal signal the DOAJ-only path missed (gold-OA venues absent from DOAJ, e.g. MDPI
 * <i>Electronics</i>).</p>
 *
 * <p>Aggregation rule per source: {@code isOa} = OR across works (any work seeing it gold-OA marks it),
 * {@code apcUsd} = max advertised {@code apc_list.value_usd} seen. A hybrid journal (paid OA option but
 * {@code is_oa=false}) keeps {@code isOa=false} and is not a fee journal downstream.</p>
 */
@Service
@RequiredArgsConstructor
public class OpenAlexSourceApcImportService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexSourceApcImportService.class);
    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";
    private static final int SAVE_BATCH = 500;

    private final ObjectMapper objectMapper;
    private final OpenAlexSourceFactRepository sourceFactRepository;

    public record ImportResult(long worksScanned, long sourcesUpserted, long feeSources) {}

    /**
     * Stream the given works dumps, aggregate per-source APC, and upsert one {@link OpenAlexSourceFact}
     * per venue. Missing files are skipped with a warning. Idempotent (upsert by source id).
     */
    public ImportResult importSourceApc(List<Path> worksFiles, String batchId, String correlationId)
            throws IOException {
        Map<String, Aggregate> bySource = new LinkedHashMap<>();
        long scanned = 0;
        for (Path file : worksFiles) {
            if (file == null || !Files.isRegularFile(file)) {
                log.warn("OpenAlex source APC import: works file missing, skipping ({})", file);
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    OpenAlexWorksResponse.OpenAlexWork work =
                            objectMapper.readValue(line, OpenAlexWorksResponse.OpenAlexWork.class);
                    scanned++;
                    accumulate(bySource, work);
                    if (scanned % 50_000 == 0) {
                        log.info("OpenAlex source APC import: {} works scanned, {} sources so far...",
                                scanned, bySource.size());
                    }
                }
            }
            log.info("OpenAlex source APC import: finished {} ({} works total, {} sources)",
                    file.getFileName(), scanned, bySource.size());
        }

        Instant now = Instant.now();
        List<OpenAlexSourceFact> buffer = new ArrayList<>(SAVE_BATCH);
        long upserted = 0;
        long fee = 0;
        for (Aggregate agg : bySource.values()) {
            OpenAlexSourceFact fact = agg.toFact(batchId, correlationId, now);
            buffer.add(fact);
            upserted++;
            if (fact.isFeeJournal()) {
                fee++;
            }
            if (buffer.size() >= SAVE_BATCH) {
                sourceFactRepository.saveAll(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            sourceFactRepository.saveAll(buffer);
        }
        log.info("OpenAlex source APC import: {} works scanned, {} sources upserted ({} fee journals).",
                scanned, upserted, fee);
        return new ImportResult(scanned, upserted, fee);
    }

    private void accumulate(Map<String, Aggregate> bySource, OpenAlexWorksResponse.OpenAlexWork work) {
        OpenAlexWorksResponse.PrimaryLocation loc = work.getPrimary_location();
        OpenAlexWorksResponse.OpenAlexSource src = loc == null ? null : loc.getSource();
        if (src == null) {
            return;
        }
        String id = strip(src.getId());
        if (id == null || id.isBlank()) {
            return;
        }
        Aggregate agg = bySource.computeIfAbsent(id, k -> new Aggregate(id));
        agg.observe(src, apcUsd(work.getApc_list()));
    }

    private static Integer apcUsd(OpenAlexWorksResponse.Apc apc) {
        return apc == null ? null : apc.getValue_usd();
    }

    private static String strip(String id) {
        if (id == null) {
            return null;
        }
        return id.startsWith(OPENALEX_ID_PREFIX) ? id.substring(OPENALEX_ID_PREFIX.length()) : id;
    }

    /** Per-source running aggregate over the streamed works. */
    private static final class Aggregate {
        private final String id;
        private String displayName;
        private final LinkedHashSet<String> issns = new LinkedHashSet<>();
        private boolean isOa;
        private Boolean isInDoaj;
        private int apcUsd;
        private int works;

        Aggregate(String id) {
            this.id = id;
        }

        void observe(OpenAlexWorksResponse.OpenAlexSource src, Integer worksApcUsd) {
            works++;
            if (displayName == null && src.getDisplay_name() != null) {
                displayName = src.getDisplay_name();
            }
            if (src.getIssn() != null) {
                issns.addAll(src.getIssn());
            }
            if (Boolean.TRUE.equals(src.getIs_oa())) {
                isOa = true;
            }
            if (Boolean.TRUE.equals(src.getIs_in_doaj())) {
                isInDoaj = true;
            } else if (isInDoaj == null && src.getIs_in_doaj() != null) {
                isInDoaj = false;
            }
            if (worksApcUsd != null && worksApcUsd > apcUsd) {
                apcUsd = worksApcUsd;
            }
        }

        OpenAlexSourceFact toFact(String batchId, String correlationId, Instant now) {
            OpenAlexSourceFact fact = new OpenAlexSourceFact();
            fact.setId(id);
            fact.setDisplayName(displayName);
            fact.setIssns(new ArrayList<>(issns));
            fact.setIsOa(isOa);
            fact.setIsInDoaj(isInDoaj);
            fact.setApcUsd(apcUsd > 0 ? apcUsd : null);
            fact.setWorksObserved(works);
            fact.setSource("OPENALEX_WORKS_DUMP");
            fact.setSourceBatchId(batchId);
            fact.setSourceCorrelationId(correlationId);
            fact.setCreatedAt(now);
            fact.setUpdatedAt(now);
            return fact;
        }
    }
}
