package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportViewAssembler;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Division (Faculty / Institute / Service)-level roll-up. Aggregates researchers across
 * every department directly under the division. Joint-appointed researchers under multiple
 * departments are de-duplicated; the view model records which department contributed each
 * researcher for grouping in the UI. Scores come from each member's latest persisted report
 * run (what the researcher sees in their workspace) — the view never recomputes.
 */
@Service
@RequiredArgsConstructor
public class DivisionReportFacade {

    private final OrgDivisionRepository orgDivisionRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final OrgUnitReportViewAssembler orgUnitReportViewAssembler;
    private final OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;
    private final ReportVisibilityService reportVisibilityService;
    private final ro.uvt.pokedex.core.service.application.reporting.PromotionReadinessService promotionReadinessService;

    /**
     * @deprecated use {@link #listReportsVisibleForDivision(String)} so the listing respects
     * the division-head selection.
     */
    @Deprecated
    public List<IndividualReport> listAvailableReports() {
        return individualReportRepository.findAll();
    }

    public List<IndividualReport> listReportsVisibleForDivision(String divisionId) {
        return reportVisibilityService.listVisibleReportsForDivision(divisionId);
    }

    public Optional<OrgDivision> findDivision(String divisionId) {
        return orgDivisionRepository.findById(divisionId);
    }

    public Optional<OrgUnitReportViewModel> buildView(String divisionId, String reportId, Instant compareTo) {
        Optional<OrgDivision> divOpt = orgDivisionRepository.findById(divisionId);
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (divOpt.isEmpty() || reportOpt.isEmpty()) return Optional.empty();
        OrgDivision division = divOpt.get();
        IndividualReport report = reportOpt.get();

        List<OrgUnitRosterService.RosterMember> members = orgUnitRosterService.divisionRoster(divisionId);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(members, report, compareTo);
        List<OrgUnitReportViewModel.CompareOption> compareOptions = orgUnitReportViewAssembler.toCompareOptions(
                orgUnitReportRefreshEventRepository.findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(
                        OrgUnitReportRefreshEvent.UnitType.DIVISION, divisionId, reportId));
        return Optional.of(orgUnitReportViewAssembler.toViewModel(
                division.getId(), division.getName(), report, rollup, compareOptions));
    }

    /** Promotion-readiness board for the division against the given report's next-rung thresholds. */
    public Optional<PromotionBoardView> buildPromotionBoard(String divisionId, String reportId) {
        return buildPromotionBoard(divisionId, reportId, java.util.Set.of());
    }

    /** @param excludedCriteria criterion indices toggled off for this view — see PromotionReadinessService. */
    public Optional<PromotionBoardView> buildPromotionBoard(String divisionId, String reportId,
                                                            java.util.Set<Integer> excludedCriteria) {
        Optional<OrgDivision> divOpt = orgDivisionRepository.findById(divisionId);
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (divOpt.isEmpty() || reportOpt.isEmpty()) return Optional.empty();
        IndividualReport report = reportOpt.get();
        List<OrgUnitRosterService.RosterMember> members = orgUnitRosterService.divisionRoster(divisionId);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(members, report, null);
        return Optional.of(new PromotionBoardView(divOpt.get().getName(), report,
                promotionReadinessService.build(report, rollup, excludedCriteria)));
    }

    public record PromotionBoardView(String unitName, IndividualReport report,
                                     ro.uvt.pokedex.core.service.application.reporting.PromotionReadinessService.PromotionBoard board) {}
}
