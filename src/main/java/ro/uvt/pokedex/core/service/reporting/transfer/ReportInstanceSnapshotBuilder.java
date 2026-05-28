package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.ActivityBlockProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.ActivityRowProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.CitationRowProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.PublicationRowProjector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ReportInstanceSnapshotBuilder {

    private final PublicationRowProjector publicationProjector;
    private final CitationRowProjector citationProjector;
    private final ActivityBlockProjector activityBlockProjector;
    private final ReportImportRegistry registry;

    public ReportInstanceSnapshotBuilder(PublicationRowProjector publicationProjector,
                                         CitationRowProjector citationProjector,
                                         ActivityBlockProjector activityBlockProjector,
                                         ReportImportRegistry registry) {
        this.publicationProjector = publicationProjector;
        this.citationProjector = citationProjector;
        this.activityBlockProjector = activityBlockProjector;
        this.registry = registry;
    }

    public ReportInstanceSnapshot build(UserIndividualReportRun run,
                                        IndividualReport report,
                                        String userEmail) {
        ReportInstanceSnapshot snapshot = new ReportInstanceSnapshot();
        snapshot.setReportTypeKey(report.getReportTypeKey());
        snapshot.setReportDefinitionId(report.getId());
        snapshot.setSourceRunId(run != null ? run.getId() : null);
        snapshot.setExportedAt(Instant.now());
        snapshot.setExportedBy(userEmail);

        Map<String, String> roles = report.getIndicatorRolesByIndicatorId();
        if (roles == null || report.getIndicators() == null) {
            return snapshot;
        }

        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) continue;
            String roleKey = roles.get(indicator.getId());
            if (roleKey == null || roleKey.isBlank()) continue;

            switch (roleKey) {
                case PublicationRowProjector.ROLE_JOURNAL,
                     PublicationRowProjector.ROLE_CONFERENCE -> {
                    List<PublicationSnapshotItem> items = publicationProjector.project(userEmail, indicator, roleKey);
                    snapshot.getItems().addAll(items);
                }
                case CitationRowProjector.ROLE_KEY -> {
                    List<CitationSnapshotItem> tiles = citationProjector.project(userEmail, indicator, roleKey);
                    snapshot.getItems().addAll(tiles);
                }
                default -> {
                    // Activity-block roles are populated below via the block-driven projector.
                    // Unknown role — left unpopulated; admin will see empty sheet sections.
                }
            }
        }

        // STACKED_BLOCKS roles are block-driven, not indicator-iteration-driven: each block on the
        // binding declares which indicator feeds it via report.indicatorByActivityBlockKey. This is
        // what lets a publication-typed indicator (e.g. books/chapters) feed a block that lives
        // alongside activity-typed indicator blocks on the same sheet.
        if (report.getReportTypeKey() != null) {
            registry.find(report.getReportTypeKey()).ifPresent(support ->
                    snapshot.getItems().addAll(
                            activityBlockProjector.projectAllBlocks(userEmail, report, support.binding())));
        }

        return snapshot;
    }
}
