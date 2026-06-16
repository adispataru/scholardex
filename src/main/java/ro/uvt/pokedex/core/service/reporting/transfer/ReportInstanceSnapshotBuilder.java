package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.ActivityBlockProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.CitationRowProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.PublicationRowProjector;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ReportInstanceSnapshotBuilder {

    private final PublicationRowProjector publicationProjector;
    private final CitationRowProjector citationProjector;
    private final ActivityBlockProjector activityBlockProjector;
    private final ReportImportRegistry registry;
    private final UserIndicatorResultService userIndicatorResultService;
    private final RunIndicatorSnapshotProjector runProjector;

    public ReportInstanceSnapshotBuilder(PublicationRowProjector publicationProjector,
                                         CitationRowProjector citationProjector,
                                         ActivityBlockProjector activityBlockProjector,
                                         ReportImportRegistry registry,
                                         UserIndicatorResultService userIndicatorResultService,
                                         RunIndicatorSnapshotProjector runProjector) {
        this.publicationProjector = publicationProjector;
        this.citationProjector = citationProjector;
        this.activityBlockProjector = activityBlockProjector;
        this.registry = registry;
        this.userIndicatorResultService = userIndicatorResultService;
        this.runProjector = runProjector;
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

        Map<String, IndicatorApplyResultDto> runResultsByIndicatorId = runResultsByIndicatorId(run);
        boolean useRunResults = !runResultsByIndicatorId.isEmpty();

        Map<String, String> roles = report.getIndicatorRolesByIndicatorId();
        if (roles == null || report.getIndicators() == null) {
            return snapshot;
        }

        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) continue;
            String roleKey = roles.get(indicator.getId());
            // Publication/citation indicators dispatch on their role; the block field is N/A for
            // them and the admin form stores the __not_exported__ sentinel there by default. The
            // combined role-OR-block exclusion check would therefore wrongly drop every valid-role
            // publication/citation indicator (export shows 0). Exclude here on the *role* only;
            // activity-block exclusion remains block-driven in the projectors below.
            if (roleKey == null || roleKey.isBlank()
                    || ReportExportReadinessValidator.isExcludedFromTemplate(roleKey)) continue;

            switch (roleKey) {
                case PublicationRowProjector.ROLE_JOURNAL,
                     PublicationRowProjector.ROLE_CONFERENCE,
                     PublicationRowProjector.ROLE_JOURNAL_RECENT,
                     PublicationRowProjector.ROLE_BOOKS -> {
                    // ROLE_JOURNAL_RECENT carries its items (tagged with the role) so a no-formula
                    // template can mark which publications are "recent" and total them separately;
                    // the renderer is responsible for not listing them as duplicate rows.
                    List<PublicationSnapshotItem> items = useRunResults
                            ? runProjector.projectPublication(runResultsByIndicatorId.get(indicator.getId()), roleKey)
                            : publicationProjector.project(userEmail, indicator, roleKey);
                    snapshot.getItems().addAll(items);
                }
                case CitationRowProjector.ROLE_KEY -> {
                    List<CitationSnapshotItem> tiles = useRunResults
                            ? runProjector.projectCitations(runResultsByIndicatorId.get(indicator.getId()), roleKey)
                            : citationProjector.project(userEmail, indicator, roleKey);
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
            registry.find(report.getReportTypeKey()).ifPresent(support -> {
                if (useRunResults) {
                    snapshot.getItems().addAll(
                            runProjector.projectActivityBlocks(report, support.binding(), runResultsByIndicatorId,
                                    support::formatBlockPublicationDescription));
                } else {
                    snapshot.getItems().addAll(
                            activityBlockProjector.projectAllBlocks(userEmail, report, support.binding(),
                                    support::formatBlockPublicationDescription));
                }
            });
        }

        populateTotals(snapshot, run, report);
        return snapshot;
    }

    /**
     * Per-role / per-block totals from the run's dedicated indicator scores, for no-formula (DOCX)
     * templates. Keyed by activity block name when the indicator maps to a block, otherwise by its
     * role key. Each role/block accumulates the scores of the indicators mapped to it — so e.g. the
     * recent-publications role total comes from its own indicator, distinct from the all-publications
     * role. XLSX templates ignore this (their formulas compute totals).
     */
    private void populateTotals(ReportInstanceSnapshot snapshot, UserIndividualReportRun run, IndividualReport report) {
        if (run == null || run.getIndicatorScoresByIndicatorId() == null
                || report.getIndicators() == null || report.getIndicatorRolesByIndicatorId() == null) {
            return;
        }
        Map<String, String> roles = report.getIndicatorRolesByIndicatorId();
        Map<String, String> blocks = report.getBlockByIndicatorId();
        Map<String, Double> scores = run.getIndicatorScoresByIndicatorId();
        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) continue;
            String role = roles.get(indicator.getId());
            if (role == null || role.isBlank() || ReportExportReadinessValidator.isExcludedFromTemplate(role)) continue;
            String block = blocks != null ? blocks.get(indicator.getId()) : null;
            boolean blockUsable = block != null && !block.isBlank()
                    && !ReportExportReadinessValidator.isExcludedFromTemplate(block);
            String key = blockUsable ? block : role;
            // Enforce the per-indicator cap (e.g. Info_D_ix "maximum 24 puncte") at the snapshot
            // boundary too, so the exported total honours it independently of the upstream run
            // score, and each indicator's contribution is capped before it is summed into a block.
            double score = indicator.applyPointsCap(scores.getOrDefault(indicator.getId(), 0.0));
            snapshot.getTotals().merge(key, score, Double::sum);
        }
    }

    private Map<String, IndicatorApplyResultDto> runResultsByIndicatorId(UserIndividualReportRun run) {
        if (run == null || run.getIndicatorResultIds() == null || run.getIndicatorResultIds().isEmpty()) {
            return Map.of();
        }
        Map<String, IndicatorApplyResultDto> out = new LinkedHashMap<>();
        for (String resultId : run.getIndicatorResultIds()) {
            if (resultId == null || resultId.isBlank()) continue;
            Optional<IndicatorApplyResultDto> result = userIndicatorResultService.getById(resultId);
            result.ifPresent(dto -> {
                if (dto.indicatorId() != null) {
                    out.put(dto.indicatorId(), dto);
                }
            });
        }
        return out;
    }
}
