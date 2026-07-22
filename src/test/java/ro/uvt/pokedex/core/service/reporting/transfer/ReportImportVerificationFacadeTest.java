package ro.uvt.pokedex.core.service.reporting.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparisonService;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.ActivityBlockProjector;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportImportVerificationFacadeTest {

    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock
    private ReportInstanceSnapshotBuilder snapshotBuilder;
    @Mock
    private ReportImportRegistry registry;
    @Mock
    private ReportScoreComparisonService comparisonService;
    @Mock
    private ReportExportReadinessValidator readinessValidator;
    @Mock
    private ReportTypeImportSupport support;
    @Mock
    private ActivityBlockProjector activityBlockProjector;

    @InjectMocks
    private ReportImportVerificationFacade facade;

    @BeforeEach
    void setUp() {
        // Default: no report-defined activity blocks — most tests don't exercise the
        // bound-activity-options wiring, only verifyImportableGrantActivityOptionsIncludeUnboundReportActivities does.
        lenient().when(activityBlockProjector.buildIndicatorsByBlock(any())).thenReturn(Map.of());
    }

    @Test
    void verifyUsesRequestedRunAsPrimaryPlatformComparison() {
        IndividualReport report = report();
        UserIndividualReportRun selectedRun = run("run-selected", Instant.parse("2026-04-01T10:00:00Z"));
        ReportScoreComparison comparison = emptyComparison(10, 10);

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.parse(any(), eq(ReportFormat.XLSX), any())).thenReturn(List.of());
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(selectedRun));
        when(userIndividualReportRunRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "report-1"))
                .thenReturn(Optional.of(selectedRun));
        when(snapshotBuilder.build(any(UserIndividualReportRun.class), eq(report), eq("u@uvt.ro")))
                .thenReturn(snapshot("run-selected"));
        when(comparisonService.compare(any(), any(), any())).thenReturn(comparison);

        Optional<ReportImportVerificationFacade.VerificationResult> result = facade.verify(
                "u@uvt.ro",
                "report-1",
                "run-selected",
                ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[]{1}));

        assertTrue(result.isPresent());
        assertEquals(comparison, result.get().displayedRunComparison());
        ArgumentCaptor<UserIndividualReportRun> runCaptor = ArgumentCaptor.forClass(UserIndividualReportRun.class);
        verify(snapshotBuilder).build(runCaptor.capture(), eq(report), eq("u@uvt.ro"));
        assertEquals("run-selected", runCaptor.getValue().getId());
    }

    @Test
    void verifyAddsCurrentRunComparisonWhenLatestRunDiffersFromDisplayedRun() {
        IndividualReport report = report();
        UserIndividualReportRun displayedRun = run("run-old", Instant.parse("2026-04-01T10:00:00Z"));
        UserIndividualReportRun latestRun = run("run-new", Instant.parse("2026-04-02T10:00:00Z"));
        ReportScoreComparison displayedComparison = emptyComparison(10, 10);
        ReportScoreComparison currentComparison = emptyComparison(12, 10);

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.parse(any(), eq(ReportFormat.XLSX), any())).thenReturn(List.of());
        when(userIndividualReportRunRepository.findById("run-old")).thenReturn(Optional.of(displayedRun));
        when(userIndividualReportRunRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "report-1"))
                .thenReturn(Optional.of(latestRun));
        when(snapshotBuilder.build(displayedRun, report, "u@uvt.ro")).thenReturn(snapshot("run-old"));
        when(snapshotBuilder.build(latestRun, report, "u@uvt.ro")).thenReturn(snapshot("run-new"));
        when(comparisonService.compare(any(), any(), any())).thenReturn(displayedComparison, currentComparison);

        Optional<ReportImportVerificationFacade.VerificationResult> result = facade.verify(
                "u@uvt.ro",
                "report-1",
                "run-old",
                ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[]{1}));

        assertTrue(result.isPresent());
        assertEquals("run-old", result.get().displayedRunId());
        assertEquals("run-new", result.get().currentRunId());
        assertEquals(displayedComparison, result.get().displayedRunComparison());
        assertEquals(currentComparison, result.get().currentRunComparison());
    }

    @Test
    void verifyOutcomeDistinguishesForbiddenRunFromMissingRun() {
        IndividualReport report = report();
        UserIndividualReportRun otherUsersRun = run("run-selected", Instant.parse("2026-04-01T10:00:00Z"));
        otherUsersRun.setUserEmail("other@uvt.ro");

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.parse(any(), eq(ReportFormat.XLSX), any())).thenReturn(List.of());
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(otherUsersRun));

        ReportImportVerificationFacade.VerificationOutcome outcome = facade.verifyOutcome(
                "u@uvt.ro",
                "report-1",
                "run-selected",
                ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[]{1}));

        assertEquals(ReportImportVerificationFacade.VerificationFailureReason.FORBIDDEN_RUN, outcome.failureReason());
    }

    @Test
    void verifyOutcomeReportsInvalidWorkbookSeparately() {
        IndividualReport report = report();

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.parse(any(), eq(ReportFormat.XLSX), any())).thenThrow(new IllegalArgumentException("bad workbook"));

        ReportImportVerificationFacade.VerificationOutcome outcome = facade.verifyOutcome(
                "u@uvt.ro",
                "report-1",
                "run-selected",
                ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[]{1}));

        assertEquals(ReportImportVerificationFacade.VerificationFailureReason.INVALID_WORKBOOK, outcome.failureReason());
    }

    @Test
    void verifyOutcomeDistinguishesKnownFormatWithoutParserFromUnsupportedFormat() {
        IndividualReport report = report();

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.DOCX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));

        ReportImportVerificationFacade.VerificationOutcome outcome = facade.verifyOutcome(
                "u@uvt.ro",
                "report-1",
                "run-selected",
                ReportFormat.DOCX,
                new ByteArrayInputStream(new byte[]{1}));

        assertEquals(ReportImportVerificationFacade.VerificationFailureReason.PARSER_NOT_AVAILABLE, outcome.failureReason());
    }

    @Test
    void verifyPassesBoundActivityOptionsFromReportDefinitionThroughToComparisonService() {
        // Regression: a researcher with ZERO existing "Granturi" activities must still get a real
        // candidate Activity type for the inline "Add to platform" form — sourced from the report
        // definition's block bindings, not only from what the researcher already has on the platform.
        IndividualReport report = report();
        UserIndividualReportRun selectedRun = run("run-selected", Instant.parse("2026-04-01T10:00:00Z"));
        ReportScoreComparison comparison = emptyComparison(0, 21);

        Activity grantActivity = new Activity();
        grantActivity.setId("act-grant-cercetare");
        grantActivity.setName("Grant Cercetare");
        Indicator grantIndicator = new Indicator();
        grantIndicator.setId("ind-1");
        grantIndicator.setActivity(grantActivity);

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.parse(any(), eq(ReportFormat.XLSX), any())).thenReturn(List.of());
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(selectedRun));
        when(userIndividualReportRunRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "report-1"))
                .thenReturn(Optional.of(selectedRun));
        when(snapshotBuilder.build(any(UserIndividualReportRun.class), eq(report), eq("u@uvt.ro")))
                .thenReturn(snapshot("run-selected"));
        when(activityBlockProjector.buildIndicatorsByBlock(report)).thenReturn(Map.of("Granturi", List.of(grantIndicator)));
        when(comparisonService.compare(any(), any(), any())).thenReturn(comparison);

        facade.verify("u@uvt.ro", "report-1", "run-selected", ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[]{1}));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<ReportScoreComparison.ActivityOption>>> optionsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(comparisonService).compare(any(), any(), optionsCaptor.capture());
        Map<String, List<ReportScoreComparison.ActivityOption>> boundOptions = optionsCaptor.getValue();
        assertEquals(List.of(new ReportScoreComparison.ActivityOption("act-grant-cercetare", "Grant Cercetare")),
                boundOptions.get("Granturi"));
    }

    private static IndividualReport report() {
        IndividualReport report = new IndividualReport();
        report.setId("report-1");
        report.setTitle("Report One");
        report.setReportTypeKey("informatica-2016");
        report.setImportEnabled(true);
        return report;
    }

    private static UserIndividualReportRun run(String id, Instant createdAt) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail("u@uvt.ro");
        run.setReportDefinitionId("report-1");
        run.setCreatedAt(createdAt);
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }

    private static ReportInstanceSnapshot snapshot(String runId) {
        ReportInstanceSnapshot snapshot = new ReportInstanceSnapshot();
        snapshot.setSourceRunId(runId);
        return snapshot;
    }

    private static ReportScoreComparison emptyComparison(double platformTotal, double fileTotal) {
        return new ReportScoreComparison(List.of(), List.of(), platformTotal, fileTotal, 0, 0, 0);
    }
}
