package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel;
import ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel.CriterionComparisonRow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Criterion-level comparison between a researcher's latest runs of two "compatible" report
 * definitions — today just FV Info 2016 vs FV Info 2026, whose criteria happen to share names
 * verbatim (H81 standards migration kept "Perspectiva B/C/D" etc. unchanged, adding only
 * "Publicații A*+A"). Matching is by criterion name, not by any stored linkage — there is none —
 * so a report pair only qualifies when {@link #OLDER_TYPE_KEY_BY_NEWER} says so. Eligibilitate PD
 * 2016/2026 share no indicators or criterion names and are deliberately NOT registered here: PD is
 * a different eligibility standard, not a career-progression comparison.
 */
@Service
@RequiredArgsConstructor
public class ReportComparisonFacade {

    /** newer reportTypeKey → its compatible older reportTypeKey. Extend here as more pairs qualify. */
    private static final Map<String, String> OLDER_TYPE_KEY_BY_NEWER = Map.of(
            "informatica-2026", "informatica-2016"
    );

    private final UserReportFacade userReportFacade;
    private final UserIndividualReportRunService userIndividualReportRunService;

    /** A criterion pairing between two compatible reports, structural only — no scores. */
    public record CriterionColumn(String name, Integer olderIndex, Integer newerIndex) {
    }

    /**
     * The other report in {@code report}'s compatible pair, if one is configured AND assigned to
     * this researcher (both conditions must hold — there's nothing to compare against otherwise).
     */
    public Optional<IndividualReport> findCompatibleReport(String researcherEmail, IndividualReport report) {
        return resolveOtherTypeKey(report).flatMap(targetTypeKey ->
                userReportFacade.buildIndividualReportsListView(researcherEmail).individualReports().stream()
                        .filter(r -> targetTypeKey.equals(r.getReportTypeKey()))
                        .findFirst());
    }

    /**
     * The other report in {@code report}'s compatible pair, system-wide — no per-researcher
     * assignment/visibility check. For roster-wide (org-unit) views, where "is this report visible
     * to researcher X" doesn't apply; the org-unit roster itself gates who can be scored.
     */
    public Optional<IndividualReport> findCompatibleReport(IndividualReport report) {
        return resolveOtherTypeKey(report).flatMap(targetTypeKey ->
                userReportFacade.buildIndividualReportsListView(null).individualReports().stream()
                        .filter(r -> targetTypeKey.equals(r.getReportTypeKey()))
                        .findFirst());
    }

    /** True when {@code report}'s reportTypeKey is the OLDER side of a registered pair. */
    public boolean isOlder(IndividualReport report) {
        return OLDER_TYPE_KEY_BY_NEWER.containsValue(report.getReportTypeKey());
    }

    private Optional<String> resolveOtherTypeKey(IndividualReport report) {
        String typeKey = report.getReportTypeKey();
        if (typeKey == null || typeKey.isBlank()) {
            return Optional.empty();
        }
        String otherTypeKey = OLDER_TYPE_KEY_BY_NEWER.get(typeKey);
        if (otherTypeKey == null) {
            otherTypeKey = OLDER_TYPE_KEY_BY_NEWER.entrySet().stream()
                    .filter(e -> e.getValue().equals(typeKey))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
        return Optional.ofNullable(otherTypeKey);
    }

    /**
     * Builds the comparison. {@code reportA}/{@code reportB} may be passed in either order — which
     * one is "older" is resolved from {@link #OLDER_TYPE_KEY_BY_NEWER}, not argument position.
     */
    public ReportComparisonViewModel buildComparison(User researcher, IndividualReport reportA, IndividualReport reportB) {
        boolean aIsOlder = isOlder(reportA);
        IndividualReport olderReport = aIsOlder ? reportA : reportB;
        IndividualReport newerReport = aIsOlder ? reportB : reportA;

        Optional<IndividualReportRunDto> olderRun =
                userIndividualReportRunService.findLatestRun(researcher.getEmail(), olderReport.getId());
        Optional<IndividualReportRunDto> newerRun =
                userIndividualReportRunService.findLatestRun(researcher.getEmail(), newerReport.getId());

        if (olderRun.isEmpty() || newerRun.isEmpty()) {
            return new ReportComparisonViewModel(
                    olderReport, newerReport, olderRun.isPresent(), newerRun.isPresent(), List.of(),
                    null, null, null, false, false);
        }

        String position = Optional.ofNullable(researcher.getResearcherProfile())
                .map(p -> p.getPosition())
                .map(Enum::name)
                .orElse(null);

        Map<Integer, Double> olderScores = olderRun.get().criteriaScores() != null ? olderRun.get().criteriaScores() : Map.of();
        Map<Integer, Double> newerScores = newerRun.get().criteriaScores() != null ? newerRun.get().criteriaScores() : Map.of();

        List<CriterionComparisonRow> rows = buildRows(olderReport, newerReport, olderScores, newerScores, position);

        Double olderTotal = computeContributingTotal(olderReport, olderScores);
        Double newerTotal = computeContributingTotal(newerReport, newerScores);
        Double totalDelta = (olderTotal != null && newerTotal != null) ? newerTotal - olderTotal : null;

        boolean olderProvisional = olderRun.get().source() == IndividualReportRunDto.Source.ADMIN_PROVISIONAL;
        boolean newerProvisional = newerRun.get().source() == IndividualReportRunDto.Source.ADMIN_PROVISIONAL;

        return new ReportComparisonViewModel(
                olderReport, newerReport, true, true, rows, olderTotal, newerTotal, totalDelta,
                olderProvisional, newerProvisional);
    }

    /**
     * The criterion pairing between two compatible reports, by name — structural only (no scores),
     * so it's the single source of truth shared by both the single-researcher comparison
     * ({@link #buildRows}) and the org-unit roster-wide comparison
     * ({@code OrgUnitReportComparisonService}). Order: the newer report's own criterion order, then
     * any older-only criteria appended.
     */
    public List<CriterionColumn> matchCriteriaColumns(IndividualReport olderReport, IndividualReport newerReport) {
        List<AbstractReport.Criterion> olderCriteria = olderReport.getCriteria() == null ? List.of() : olderReport.getCriteria();
        List<AbstractReport.Criterion> newerCriteria = newerReport.getCriteria() == null ? List.of() : newerReport.getCriteria();

        Map<String, Integer> olderIndexByName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < olderCriteria.size(); i++) {
            olderIndexByName.put(normalize(displayName(olderCriteria.get(i), i)), i);
        }

        List<CriterionColumn> columns = new ArrayList<>();
        Set<String> matchedOlderNames = new HashSet<>();

        for (int i = 0; i < newerCriteria.size(); i++) {
            String name = displayName(newerCriteria.get(i), i);
            Integer olderIdx = olderIndexByName.get(normalize(name));
            if (olderIdx != null) {
                matchedOlderNames.add(normalize(name));
            }
            columns.add(new CriterionColumn(name, olderIdx, i));
        }

        // Criteria that exist only in the older report (none for the Info pair today, but a future
        // pair might drop one) — appended after the newer report's own order.
        for (int i = 0; i < olderCriteria.size(); i++) {
            String name = displayName(olderCriteria.get(i), i);
            if (matchedOlderNames.contains(normalize(name))) {
                continue;
            }
            columns.add(new CriterionColumn(name, i, null));
        }

        return columns;
    }

    private List<CriterionComparisonRow> buildRows(
            IndividualReport olderReport,
            IndividualReport newerReport,
            Map<Integer, Double> olderScores,
            Map<Integer, Double> newerScores,
            String position
    ) {
        List<AbstractReport.Criterion> olderCriteria = olderReport.getCriteria() == null ? List.of() : olderReport.getCriteria();
        List<AbstractReport.Criterion> newerCriteria = newerReport.getCriteria() == null ? List.of() : newerReport.getCriteria();

        List<CriterionComparisonRow> rows = new ArrayList<>();
        for (CriterionColumn column : matchCriteriaColumns(olderReport, newerReport)) {
            if (column.olderIndex() != null && column.newerIndex() != null) {
                AbstractReport.Criterion oc = olderCriteria.get(column.olderIndex());
                AbstractReport.Criterion nc = newerCriteria.get(column.newerIndex());
                double olderScore = olderScores.getOrDefault(column.olderIndex(), 0.0);
                double newerScore = newerScores.getOrDefault(column.newerIndex(), 0.0);
                Boolean olderMet = isMet(oc, olderScore, position);
                Boolean newerMet = isMet(nc, newerScore, position);
                rows.add(new CriterionComparisonRow(
                        column.name(), olderScore, newerScore, newerScore - olderScore, olderMet, newerMet, true, true));
            } else if (column.newerIndex() != null) {
                AbstractReport.Criterion nc = newerCriteria.get(column.newerIndex());
                double newerScore = newerScores.getOrDefault(column.newerIndex(), 0.0);
                Boolean newerMet = isMet(nc, newerScore, position);
                rows.add(new CriterionComparisonRow(column.name(), null, newerScore, null, null, newerMet, false, true));
            } else {
                AbstractReport.Criterion oc = olderCriteria.get(column.olderIndex());
                double olderScore = olderScores.getOrDefault(column.olderIndex(), 0.0);
                Boolean olderMet = isMet(oc, olderScore, position);
                rows.add(new CriterionComparisonRow(column.name(), olderScore, null, null, olderMet, null, true, false));
            }
        }
        return rows;
    }

    /** Mirrors {@code IndividualReportViewModelAssembler}'s met-status rule: null when the position has no threshold. */
    private Boolean isMet(AbstractReport.Criterion criterion, double score, String position) {
        if (criterion.getThresholds() == null || position == null || position.isBlank()) {
            return null;
        }
        boolean hasThresholdForPosition = criterion.getThresholds().stream()
                .anyMatch(t -> t.getPosition() != null && t.getPosition().name().equals(position));
        if (!hasThresholdForPosition) {
            return null;
        }
        return criterion.getThresholds().stream()
                .filter(t -> t.getPosition() != null && t.getPosition().name().equals(position))
                .anyMatch(t -> score >= t.getValue());
    }

    /** Mirrors {@code IndividualReportViewModelAssembler}'s total-score rule: sum of contributesToTotal criteria. */
    private Double computeContributingTotal(IndividualReport report, Map<Integer, Double> scores) {
        List<AbstractReport.Criterion> criteria = report.getCriteria();
        if (criteria == null) {
            return null;
        }
        boolean any = false;
        double total = 0.0;
        for (int i = 0; i < criteria.size(); i++) {
            if (criteria.get(i).isContributesToTotal()) {
                any = true;
                total += scores.getOrDefault(i, 0.0);
            }
        }
        return any ? total : null;
    }

    private String displayName(AbstractReport.Criterion criterion, int index) {
        return criterion.getName() != null && !criterion.getName().isBlank()
                ? criterion.getName().trim() : "Criterion " + (index + 1);
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).trim();
    }
}
