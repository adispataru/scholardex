package ro.uvt.pokedex.core.service.brainmap;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * H64 — standalone rebuild of ONLY the canonical project layer: (re)import the brainmap dump → derive canonical
 * {@code scholardex.project_facts} (merging user-defined/CORDIS) → project to {@code scholardex_project_view}. Touches
 * only the project collections (the publication/author/forum canonical layers are untouched), so it's a safe, scoped
 * ops trigger — no full pipeline rebuild needed to populate projects.
 */
@Service
@RequiredArgsConstructor
public class ProjectRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ProjectRebuildService.class);

    private final BrainmapProjectImportService brainmapProjectImportService;
    private final ProjectCanonicalizationService projectCanonicalizationService;
    private final ProjectProjectionService projectProjectionService;

    @Value("${core.brainmap.bulk.projects-file:}")
    private String brainmapProjectsFile;

    public record ProjectRebuildResult(int brainmapImported, int canonicalProjects, int coordinatorsResolved,
                                       int projectedRows) {
    }

    /** Run import (if a brainmap file is configured) → canonical derive → projection. Returns the counts. */
    public ProjectRebuildResult rebuild() {
        long now = System.currentTimeMillis();
        String batchId = "project-rebuild-" + now;
        int imported = 0;
        if (brainmapProjectsFile != null && !brainmapProjectsFile.isBlank()) {
            try {
                imported = brainmapProjectImportService
                        .importAll(Path.of(brainmapProjectsFile), batchId, "project-rebuild")
                        .projectsImported();
            } catch (IOException e) {
                throw new IllegalStateException("Brainmap projects import failed: " + e.getMessage(), e);
            }
        }
        var canon = projectCanonicalizationService.rebuild(batchId, "project-rebuild");
        int projected = projectProjectionService.rebuildView();
        log.info("Project rebuild complete: brainmapImported={} canonical={} coordinatorsResolved={} projected={}",
                imported, canon.canonicalProjects(), canon.coordinatorsResolved(), projected);
        return new ProjectRebuildResult(imported, canon.canonicalProjects(), canon.coordinatorsResolved(), projected);
    }
}
