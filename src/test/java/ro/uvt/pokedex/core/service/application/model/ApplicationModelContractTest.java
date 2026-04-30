package ro.uvt.pokedex.core.service.application.model;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModelContractTest {

    @Test
    void adminDashboardHasErrorsOnlyForNonEmptyErrorsList() {
        AdminDashboardViewModel noErrors = new AdminDashboardViewModel(
                1L, 2L, 3L, 4L, 5L,
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                List.of(),
                List.of()
        );
        AdminDashboardViewModel nullErrors = new AdminDashboardViewModel(
                1L, 2L, 3L, 4L, 5L,
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                List.of(),
                null
        );
        AdminDashboardViewModel withErrors = new AdminDashboardViewModel(
                1L, 2L, 3L, 4L, 5L,
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                List.of(),
                List.of("aggregation-failed")
        );

        assertFalse(noErrors.hasErrors());
        assertFalse(nullErrors.hasErrors());
        assertTrue(withErrors.hasErrors());
    }

    @Test
    void adminOperationStatusNeverRunAndHasRunContract() {
        AdminOperationStatus neverRun = AdminOperationStatus.neverRun();
        AdminOperationStatus run = new AdminOperationStatus(Instant.parse("2026-01-02T10:15:30Z"), "SUCCESS", "ok");

        assertNull(neverRun.lastRunAt());
        assertEquals(AdminOperationStatus.OUTCOME_NEVER_RUN, neverRun.outcome());
        assertFalse(neverRun.hasRun());
        assertTrue(run.hasRun());
    }

    @Test
    void citationsViewPaginationBoundaries() {
        ScholardexCitationsView firstPage = new ScholardexCitationsView(
                null, null, List.of(), null, null, 5L, 0, 2, 3
        );
        ScholardexCitationsView middlePage = new ScholardexCitationsView(
                null, null, List.of(), null, null, 5L, 1, 2, 3
        );
        ScholardexCitationsView lastPage = new ScholardexCitationsView(
                null, null, List.of(), null, null, 5L, 2, 2, 3
        );

        assertFalse(firstPage.hasPrevious());
        assertTrue(firstPage.hasNext());
        assertTrue(middlePage.hasPrevious());
        assertTrue(middlePage.hasNext());
        assertTrue(lastPage.hasPrevious());
        assertFalse(lastPage.hasNext());
    }

    @Test
    void fromStepBuildsSummaryAndClampsNegativeDurationAndPreserved() {
        MigrationStepResult step = new MigrationStepResult(
                "enrich-category-rankings",
                true,
                2,
                0,
                5,
                3,
                4,
                "done",
                List.of("sample"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        Instant startedAt = Instant.parse("2026-01-03T12:00:10Z");
        Instant completedAt = Instant.parse("2026-01-03T12:00:00Z");
        WosEnrichmentRunSummaryDto summary = WosEnrichmentRunSummaryDto.fromStep(step, startedAt, completedAt);

        assertEquals("enrich-category-rankings", summary.stepName());
        assertTrue(summary.executed());
        assertEquals(0L, summary.durationMs());
        assertEquals(2, summary.processed());
        assertEquals(5, summary.computed());
        assertEquals(4, summary.failed());
        assertEquals(0, summary.preserved());
        assertEquals(3, summary.skipped());
        assertEquals("done", summary.note());
    }

    @Test
    void fromStepWithNullStepUsesDefaults() {
        Instant startedAt = Instant.parse("2026-01-03T12:00:00Z");
        Instant completedAt = Instant.parse("2026-01-03T12:00:05Z");

        WosEnrichmentRunSummaryDto summary = WosEnrichmentRunSummaryDto.fromStep(null, startedAt, completedAt);

        assertEquals("enrich-category-rankings", summary.stepName());
        assertFalse(summary.executed());
        assertEquals(5_000L, summary.durationMs());
        assertEquals(0, summary.processed());
        assertEquals(0, summary.computed());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.preserved());
        assertEquals(0, summary.skipped());
        assertEquals("not-run", summary.note());
    }

    @Test
    void notRunReturnsCanonicalDefaults() {
        WosEnrichmentRunSummaryDto summary = WosEnrichmentRunSummaryDto.notRun();
        assertEquals("enrich-category-rankings", summary.stepName());
        assertFalse(summary.executed());
        assertNull(summary.startedAt());
        assertNull(summary.completedAt());
        assertEquals(0L, summary.durationMs());
        assertEquals(0, summary.processed());
        assertEquals(0, summary.computed());
        assertEquals(0, summary.preserved());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals("not-run", summary.note());
    }

    @Test
    void userWorkbookExportResultFactoryMethodsExposeExpectedPayload() {
        byte[] payload = new byte[]{1, 2, 3};
        UserWorkbookExportResult ok = UserWorkbookExportResult.ok(
                payload,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "report.xlsx"
        );
        UserWorkbookExportResult unauthorized = UserWorkbookExportResult.unauthorized();
        UserWorkbookExportResult notFound = UserWorkbookExportResult.notFound();

        assertEquals(UserWorkbookExportStatus.OK, ok.status());
        assertTrue(payload == ok.workbookBytes());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ok.contentType());
        assertEquals("report.xlsx", ok.fileName());

        assertEquals(UserWorkbookExportStatus.UNAUTHORIZED, unauthorized.status());
        assertNull(unauthorized.workbookBytes());
        assertNull(unauthorized.contentType());
        assertNull(unauthorized.fileName());

        assertEquals(UserWorkbookExportStatus.NOT_FOUND, notFound.status());
        assertNull(notFound.workbookBytes());
        assertNull(notFound.contentType());
        assertNull(notFound.fileName());
    }
}
