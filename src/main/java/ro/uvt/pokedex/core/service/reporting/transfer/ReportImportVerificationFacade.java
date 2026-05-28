package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparisonService;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Read-only score verification for the H50.3 import flow: parse an uploaded report file, compare
 * its per-item scores against the platform's latest scores, return the comparison. Never writes.
 */
@Service
public class ReportImportVerificationFacade {

    private final IndividualReportRepository individualReportRepository;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final ReportInstanceSnapshotBuilder snapshotBuilder;
    private final ReportImportRegistry registry;
    private final ReportScoreComparisonService comparisonService;

    public ReportImportVerificationFacade(IndividualReportRepository individualReportRepository,
                                          UserIndividualReportRunRepository userIndividualReportRunRepository,
                                          ReportInstanceSnapshotBuilder snapshotBuilder,
                                          ReportImportRegistry registry,
                                          ReportScoreComparisonService comparisonService) {
        this.individualReportRepository = individualReportRepository;
        this.userIndividualReportRunRepository = userIndividualReportRunRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.registry = registry;
        this.comparisonService = comparisonService;
    }

    public boolean isImportAvailable(String reportId) {
        return individualReportRepository.findById(reportId)
                .filter(IndividualReport::isImportEnabled)
                .map(r -> r.getReportTypeKey() != null
                        && registry.find(r.getReportTypeKey())
                                .map(s -> !s.supportedImportFormats().isEmpty())
                                .orElse(false))
                .orElse(false);
    }

    public Optional<ReportScoreComparison> verify(String userEmail, String reportId,
                                                  ReportFormat format, InputStream uploaded) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) return Optional.empty();
        IndividualReport report = reportOpt.get();
        if (!report.isImportEnabled() || report.getReportTypeKey() == null) return Optional.empty();

        Optional<ReportTypeImportSupport> supportOpt = registry.find(report.getReportTypeKey());
        if (supportOpt.isEmpty() || !supportOpt.get().supportedImportFormats().contains(format)) {
            return Optional.empty();
        }
        ReportTypeImportSupport support = supportOpt.get();

        // Parse the uploaded file first (consumes the stream).
        List<SnapshotItem> fileItems = support.parse(uploaded, format);

        UserIndividualReportRun run = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportId)
                .orElse(null);
        ReportInstanceSnapshot platformSnapshot = snapshotBuilder.build(run, report, userEmail);

        return Optional.of(comparisonService.compare(platformSnapshot.getItems(), fileItems));
    }
}
