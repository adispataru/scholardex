package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.ProvisionalAuthorMatch;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.Status;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProvisionalDepartmentReportServiceTest {

    @Mock
    private ProvisionalAuthorResolutionService resolutionService;
    @Mock
    private UserIndividualReportRunService runService;

    @InjectMocks
    private ProvisionalDepartmentReportService service;

    @Test
    void scoresResolvedPeopleAndFlagsAmbiguousOrUnresolvedWithoutScoring() {
        ProvisionalAuthorMatch resolved = new ProvisionalAuthorMatch(
                "adina.sasu@e-uvt.ro", "adina", "sasu", Status.RESOLVED,
                "sauth_adina", List.of("sauth_adina"), List.of("6603184963"));
        ProvisionalAuthorMatch ambiguous = new ProvisionalAuthorMatch(
                "ion.blaga@e-uvt.ro", "ion", "blaga", Status.AMBIGUOUS,
                null, List.of("sauth_x", "sauth_y"), List.of());
        ProvisionalAuthorMatch unresolved = new ProvisionalAuthorMatch(
                "dan.comanescu@e-uvt.ro", "dan", "comanescu", Status.UNRESOLVED,
                null, List.of(), List.of());

        when(resolutionService.resolveDepartment("dept-math"))
                .thenReturn(List.of(resolved, ambiguous, unresolved));
        when(runService.buildAndSaveProvisionalRun(eq("adina.sasu@e-uvt.ro"), eq("rep-math"),
                eq(List.of("sauth_adina")), eq("admin@uvt.ro")))
                .thenReturn(Optional.of(new IndividualReportRunDto(
                        "run-1", "rep-math", List.of(),
                        Map.of("ind-a", 12.0, "ind-b", 3.0), Map.of(), Map.of(1, 15.0),
                        Instant.now(), IndividualReportRunDto.Source.ADMIN_PROVISIONAL, "admin@uvt.ro")));

        var report = service.run("dept-math", "rep-math", "admin@uvt.ro");

        assertEquals(3, report.results().size());
        assertEquals(1, report.scoredCount());

        var adina = report.results().get(0);
        assertEquals(Status.RESOLVED, adina.status());
        assertEquals("run-1", adina.runId());
        assertEquals(15.0, adina.totalScore()); // sum of indicator scores 12 + 3

        var blaga = report.results().get(1);
        assertEquals(Status.AMBIGUOUS, blaga.status());
        assertNull(blaga.runId());
        assertEquals(0.0, blaga.totalScore());
        assertTrue(blaga.indicatorScores().isEmpty());

        var comanescu = report.results().get(2);
        assertEquals(Status.UNRESOLVED, comanescu.status());
        assertNull(comanescu.runId());

        // Only the RESOLVED person triggers a persisted run.
        verify(runService, never()).buildAndSaveProvisionalRun(eq("ion.blaga@e-uvt.ro"), any(), any(), any());
        verify(runService, never()).buildAndSaveProvisionalRun(eq("dan.comanescu@e-uvt.ro"), any(), any(), any());
    }
}
