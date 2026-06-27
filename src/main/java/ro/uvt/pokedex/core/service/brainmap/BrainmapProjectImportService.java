package ro.uvt.pokedex.core.service.brainmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.BrainmapProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.BrainmapProjectFactRepository;
import ro.uvt.pokedex.core.service.brainmap.dto.BrainmapProjectRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H64 slice 1 — file-driven bulk ingestion of the brainmap project dump ({@code data/brainmap/uvt_projects.jsonl})
 * into the {@code brainmap.project_facts} source collection. Mirrors {@code OpenAlexBulkImportService}: streamed
 * line-by-line (never slurped), idempotent (keyed by the brainmap {@code pkXProiectId} so a re-import upserts in
 * place). The canonical derivation ({@code ProjectCanonicalizationService}) runs separately, off these facts.
 */
@Service
@RequiredArgsConstructor
public class BrainmapProjectImportService {

    private static final Logger log = LoggerFactory.getLogger(BrainmapProjectImportService.class);
    private static final String SOURCE_BRAINMAP = "BRAINMAP";
    private static final String BUILDER_VERSION = "brainmap-project-import-v1";
    private static final int SAVE_BATCH = 500;

    private final BrainmapProjectFactRepository projectFactRepository;
    private final ObjectMapper objectMapper;

    public record BrainmapImportResult(int projectsImported, int skipped) {
    }

    /**
     * Stream {@code projectsFile} into {@code brainmap.project_facts}. A {@code null}/absent file is a no-op
     * (returns zero), mirroring the OpenAlex bulk skip semantics.
     */
    public BrainmapImportResult importAll(Path projectsFile, String batchId, String correlationId) throws IOException {
        if (projectsFile == null || !Files.isRegularFile(projectsFile)) {
            log.warn("Brainmap import: projects file missing, skipping ({})", projectsFile);
            return new BrainmapImportResult(0, 0);
        }
        int imported = 0;
        int skipped = 0;
        List<BrainmapProjectFact> buffer = new ArrayList<>(SAVE_BATCH);
        try (BufferedReader reader = Files.newBufferedReader(projectsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                BrainmapProjectRecord rec = objectMapper.readValue(line, BrainmapProjectRecord.class);
                BrainmapProjectFact fact = toFact(rec, batchId, correlationId);
                if (fact == null) {
                    skipped++;
                    continue;
                }
                buffer.add(fact);
                if (buffer.size() >= SAVE_BATCH) {
                    projectFactRepository.saveAll(buffer);
                    imported += buffer.size();
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            projectFactRepository.saveAll(buffer);
            imported += buffer.size();
        }
        log.info("Brainmap import: {} project source facts upserted ({} skipped) from {}",
                imported, skipped, projectsFile.getFileName());
        return new BrainmapImportResult(imported, skipped);
    }

    /** Map a dump line to a source fact. Requires the brainmap id ({@code pkXProiectId}); else the line is skipped. */
    BrainmapProjectFact toFact(BrainmapProjectRecord rec, String batchId, String correlationId) {
        String id = rec.getPkXProiectId() == null ? null : rec.getPkXProiectId().trim();
        if (id == null || id.isBlank()) {
            return null;
        }
        BrainmapProjectFact fact = new BrainmapProjectFact();
        fact.setId(id);
        fact.setCode(rec.getCode());
        fact.setTitle(rec.getTitle());
        fact.setPlan(rec.getPlan());
        fact.setCompetition(rec.getCompetition());
        fact.setDirectorFirst(rec.getDirectorFirst());
        fact.setDirectorLast(rec.getDirectorLast());
        fact.setDirectorRole(rec.getDirectorRole());
        fact.setCoordinator(rec.getCoordinator());
        fact.setFunder(rec.getFunder());
        fact.setStartYear(rec.getStartYear());
        fact.setEndYear(rec.getEndYear());
        fact.setDetailHref(rec.getDetailHref());
        fact.setOrgId(rec.getOrgId());
        fact.setScrapedAt(rec.getScrapedAt());
        Instant now = Instant.now();
        fact.setCreatedAt(now);
        fact.setUpdatedAt(now);
        fact.setSource(SOURCE_BRAINMAP);
        fact.setSourceRecordId(id);
        fact.setSourceBatchId(batchId);
        fact.setSourceCorrelationId(correlationId);
        fact.setBuilderVersion(BUILDER_VERSION);
        return fact;
    }
}
