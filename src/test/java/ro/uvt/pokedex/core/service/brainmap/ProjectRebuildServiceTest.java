package ro.uvt.pokedex.core.service.brainmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectRebuildServiceTest {

    @Mock
    private BrainmapProjectImportService brainmapProjectImportService;
    @Mock
    private ProjectCanonicalizationService projectCanonicalizationService;
    @Mock
    private ProjectProjectionService projectProjectionService;

    private ProjectRebuildService service(String file) {
        ProjectRebuildService s = new ProjectRebuildService(
                brainmapProjectImportService, projectCanonicalizationService, projectProjectionService);
        ReflectionTestUtils.setField(s, "brainmapProjectsFile", file);
        return s;
    }

    @Test
    void runsImportThenCanonThenProjectionAndSumsCounts() throws Exception {
        when(brainmapProjectImportService.importAll(any(Path.class), anyString(), anyString()))
                .thenReturn(new BrainmapProjectImportService.BrainmapImportResult(341, 0));
        when(projectCanonicalizationService.rebuild(anyString(), anyString()))
                .thenReturn(new ProjectCanonicalizationService.ProjectCanonResult(341, 341, 315));
        when(projectProjectionService.rebuildView()).thenReturn(341);

        ProjectRebuildService.ProjectRebuildResult r = service("data/brainmap/uvt_projects.jsonl").rebuild();

        assertThat(r.brainmapImported()).isEqualTo(341);
        assertThat(r.canonicalProjects()).isEqualTo(341);
        assertThat(r.coordinatorsResolved()).isEqualTo(315);
        assertThat(r.projectedRows()).isEqualTo(341);
    }

    @Test
    void skipsImportWhenNoFileConfiguredButStillDerivesAndProjects() throws Exception {
        when(projectCanonicalizationService.rebuild(anyString(), anyString()))
                .thenReturn(new ProjectCanonicalizationService.ProjectCanonResult(10, 10, 8));
        when(projectProjectionService.rebuildView()).thenReturn(10);

        ProjectRebuildService.ProjectRebuildResult r = service("").rebuild();

        verify(brainmapProjectImportService, never()).importAll(any(), anyString(), anyString());
        assertThat(r.brainmapImported()).isZero();
        assertThat(r.canonicalProjects()).isEqualTo(10);
        assertThat(r.projectedRows()).isEqualTo(10);
    }
}
