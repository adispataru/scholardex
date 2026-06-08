package ro.uvt.pokedex.core.service.reporting.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportExportFacadeTest {

    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock
    private ReportInstanceSnapshotBuilder snapshotBuilder;
    @Mock
    private ReportImportRegistry registry;
    @Mock
    private UserIndicatorResultService userIndicatorResultService;
    @Mock
    private ReportExportReadinessValidator readinessValidator;
    @Mock
    private ReportTypeImportSupport support;

    @InjectMocks
    private ReportExportFacade facade;

    @Test
    void exportRunUsesRequestedRunInsteadOfLatestRun() {
        IndividualReport report = report();
        UserIndividualReportRun selectedRun = run("run-selected", "u@uvt.ro", "report-1", Instant.parse("2026-04-01T10:00:00Z"));

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(selectedRun));
        when(snapshotBuilder.build(any(UserIndividualReportRun.class), eq(report), eq("u@uvt.ro")))
                .thenReturn(snapshot("run-selected"));
        when(support.render(any(ReportInstanceSnapshot.class), eq(ReportFormat.XLSX))).thenReturn(new byte[]{1, 2, 3});

        Optional<ReportExportFacade.ExportedReport> exported =
                facade.exportRun("u@uvt.ro", "report-1", "run-selected", ReportFormat.XLSX, false);

        assertTrue(exported.isPresent());
        ArgumentCaptor<UserIndividualReportRun> runCaptor = ArgumentCaptor.forClass(UserIndividualReportRun.class);
        verify(snapshotBuilder).build(runCaptor.capture(), eq(report), eq("u@uvt.ro"));
        assertEquals("run-selected", runCaptor.getValue().getId());
        verify(userIndividualReportRunRepository, never())
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "report-1");
    }

    @Test
    void exportRunRejectsRequestedRunForDifferentUser() {
        IndividualReport report = report();
        UserIndividualReportRun otherUsersRun = run("run-selected", "other@uvt.ro", "report-1", Instant.parse("2026-04-01T10:00:00Z"));

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(otherUsersRun));

        Optional<ReportExportFacade.ExportedReport> exported =
                facade.exportRun("u@uvt.ro", "report-1", "run-selected", ReportFormat.XLSX, false);

        assertTrue(exported.isEmpty());
        verify(snapshotBuilder, never()).build(any(), any(), any());
        verify(support, never()).render(any(), any());
    }

    @Test
    void exportRunOutcomeDistinguishesForbiddenRunFromMissingRun() {
        IndividualReport report = report();
        UserIndividualReportRun otherUsersRun = run("run-selected", "other@uvt.ro", "report-1", Instant.parse("2026-04-01T10:00:00Z"));

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(true);
        when(userIndividualReportRunRepository.findById("run-selected")).thenReturn(Optional.of(otherUsersRun));

        ReportExportFacade.ExportOutcome outcome =
                facade.exportRunOutcome("u@uvt.ro", "report-1", "run-selected", ReportFormat.XLSX, false);

        assertEquals(ReportExportFacade.ExportFailureReason.FORBIDDEN_RUN, outcome.failureReason());
    }

    @Test
    void exportRunOutcomeReportsInvalidConfigurationSeparately() {
        IndividualReport report = report();

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(readinessValidator.isReady(report, ReportFormat.XLSX)).thenReturn(false);

        ReportExportFacade.ExportOutcome outcome =
                facade.exportRunOutcome("u@uvt.ro", "report-1", "run-selected", ReportFormat.XLSX, false);

        assertEquals(ReportExportFacade.ExportFailureReason.NOT_READY, outcome.failureReason());
        verify(userIndividualReportRunRepository, never()).findById(any());
    }

    @Test
    void exportRunOutcomeDistinguishesKnownFormatWithoutRendererFromUnsupportedFormat() {
        IndividualReport report = report();

        when(individualReportRepository.findById("report-1")).thenReturn(Optional.of(report));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));

        ReportExportFacade.ExportOutcome outcome =
                facade.exportRunOutcome("u@uvt.ro", "report-1", "run-selected", ReportFormat.DOCX, false);

        assertEquals(ReportExportFacade.ExportFailureReason.RENDERER_NOT_AVAILABLE, outcome.failureReason());
        verify(readinessValidator, never()).isReady(any(), any());
        verify(userIndividualReportRunRepository, never()).findById(any());
    }

    private static IndividualReport report() {
        IndividualReport report = new IndividualReport();
        report.setId("report-1");
        report.setTitle("Report One");
        report.setReportTypeKey("informatica-2016");
        report.setIndicators(List.of());
        return report;
    }

    private static UserIndividualReportRun run(String id, String userEmail, String reportId, Instant createdAt) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail(userEmail);
        run.setReportDefinitionId(reportId);
        run.setCreatedAt(createdAt);
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }

    private static ReportInstanceSnapshot snapshot(String runId) {
        ReportInstanceSnapshot snapshot = new ReportInstanceSnapshot();
        snapshot.setSourceRunId(runId);
        return snapshot;
    }
}
