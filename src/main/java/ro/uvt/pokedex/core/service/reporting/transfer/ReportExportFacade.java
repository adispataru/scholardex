package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;

import java.util.Optional;

@Service
public class ReportExportFacade {

    private final IndividualReportRepository individualReportRepository;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final ReportInstanceSnapshotBuilder snapshotBuilder;
    private final ReportImportRegistry registry;
    private final UserIndicatorResultService userIndicatorResultService;

    public ReportExportFacade(IndividualReportRepository individualReportRepository,
                              UserIndividualReportRunRepository userIndividualReportRunRepository,
                              ReportInstanceSnapshotBuilder snapshotBuilder,
                              ReportImportRegistry registry,
                              UserIndicatorResultService userIndicatorResultService) {
        this.individualReportRepository = individualReportRepository;
        this.userIndividualReportRunRepository = userIndividualReportRunRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.registry = registry;
        this.userIndicatorResultService = userIndicatorResultService;
    }

    public Optional<ExportedReport> exportRun(String userEmail, String reportId, ReportFormat format) {
        return exportRun(userEmail, reportId, format, false);
    }

    public Optional<ExportedReport> exportRun(String userEmail, String reportId, ReportFormat format, boolean forceRefresh) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }
        IndividualReport report = reportOpt.get();
        if (report.getReportTypeKey() == null || report.getReportTypeKey().isBlank()) {
            return Optional.empty();
        }

        Optional<ReportTypeImportSupport> supportOpt = registry.find(report.getReportTypeKey());
        if (supportOpt.isEmpty() || !supportOpt.get().supportedExportFormats().contains(format)) {
            return Optional.empty();
        }

        if (forceRefresh && report.getIndicators() != null) {
            // Bypass the indicator-result cache so a scoring-service code change (which doesn't
            // bump the result's fingerprint) is picked up on the next export.
            for (Indicator indicator : report.getIndicators()) {
                if (indicator != null && indicator.getId() != null) {
                    userIndicatorResultService.refreshLatest(userEmail, indicator.getId());
                }
            }
        }

        UserIndividualReportRun run = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportId)
                .orElse(null);

        ReportInstanceSnapshot snapshot = snapshotBuilder.build(run, report, userEmail);
        byte[] bytes = supportOpt.get().render(snapshot, format);

        String contentType = switch (format) {
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
        String extension = format.name().toLowerCase();
        String filename = sanitize(report.getTitle()) + "." + extension;
        return Optional.of(new ExportedReport(bytes, contentType, filename));
    }

    private static String sanitize(String input) {
        if (input == null || input.isBlank()) return "report";
        return input.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public record ExportedReport(byte[] bytes, String contentType, String filename) {}
}
