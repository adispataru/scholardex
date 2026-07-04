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
import java.util.List;

/**
 * H79 — standalone (manual re-run) derivation of per-venue APC facts from the OpenAlex works dumps
 * ({@code data/openalex/uvt_works.jsonl} + {@code uvt_citing_works.jsonl}), entirely offline. Backs the
 * admin endpoint {@code POST /openalex/importSourceApc}.
 *
 * <p>The per-work aggregation is shared with the pipeline: {@link OpenAlexBulkImportService} folds the same
 * {@link OpenAlexSourceApcAggregator} into its works/citers stream, so a full rebuild produces
 * {@code openalex.source_facts} in-DAG (before the stage-4 forum-membership projection reads them). This
 * service remains for on-demand re-imports without a full rebuild.</p>
 */
@Service
@RequiredArgsConstructor
public class OpenAlexSourceApcImportService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexSourceApcImportService.class);
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
        OpenAlexSourceApcAggregator aggregator = new OpenAlexSourceApcAggregator();
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
                    aggregator.observe(work);
                    if (scanned % 50_000 == 0) {
                        log.info("OpenAlex source APC import: {} works scanned, {} sources so far...",
                                scanned, aggregator.sourceCount());
                    }
                }
            }
            log.info("OpenAlex source APC import: finished {} ({} works total, {} sources)",
                    file.getFileName(), scanned, aggregator.sourceCount());
        }

        List<OpenAlexSourceFact> facts = aggregator.toFacts(batchId, correlationId, Instant.now());
        long fee = 0;
        for (int i = 0; i < facts.size(); i += SAVE_BATCH) {
            List<OpenAlexSourceFact> chunk = facts.subList(i, Math.min(i + SAVE_BATCH, facts.size()));
            sourceFactRepository.saveAll(chunk);
            for (OpenAlexSourceFact f : chunk) {
                if (f.isFeeJournal()) {
                    fee++;
                }
            }
        }
        log.info("OpenAlex source APC import: {} works scanned, {} sources upserted ({} fee journals).",
                scanned, facts.size(), fee);
        return new ImportResult(scanned, facts.size(), fee);
    }
}
