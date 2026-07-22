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

    /**
     * The other report in {@code report}'s compatible pair, if one is configured AND assigned to
     * this researcher (both conditions must hold — there's nothing to compare against otherwise).
     */
    public Optional<IndividualReport> findCompatibleReport(String researcherEmail, IndividualReport report) {
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
        if (otherTypeKey == null) {
            return Optional.empty();
        }
        String targetTypeKey = otherTypeKey;
        return userReportFacade.buildIndividualReportsListView(researcherEmail).individualReports().stream()
                .filter(r -> targetTypeKey.equals(r.getReportTypeKey()))
                .findFirst();
    }

    /**
     * Builds the comparison. {@code reportA}/{@code reportB} may be passed in either order — which
     * one is "older" is resolved from {@link #OLDER_TYPE_KEY_BY_NEWER}, not argument position.
     */
    public ReportComparisonViewModel buildComparison(User researcher, IndividualReport reportA, IndividualReport reportB) {
        boolean aIsOlder = OLDER_TYPE_KEY_BY_NEWER.containsValue(reportA.getReportTypeKey());
        IndividualReport olderReport = aIsOlder ? reportA : reportB;
        IndividualReport newerReport = aIsOlder ? reportB : reportA;

        Optional<IndividualReportRunDto> olderRun =
                userIndividualReportRunService.findLatestRun(researcher.getEmail(), olderReport.getId());
        Optional<IndividualReportRunDto> newerRun =
                userIndividualReportRunService.findLatestRun(researcher.getEmail(), newerReport.getId());

        if (olderRun.isEmpty() || newerRun.isEmpty()) {
            return new ReportComparisonViewModel(
                    olderReport, newerReport, olderRun.isPresent(), newerRun.isPresent(), List.of(), null, null, null);
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

        return new ReportComparisonViewModel(
                olderReport, newerReport, true, true, rows, olderTotal, newerTotal, totalDelta);
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

        Map<String, Integer> olderIndexByName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < olderCriteria.size(); i++) {
            olderIndexByName.put(normalize(displayName(olderCriteria.get(i), i)), i);
        }

        List<CriterionComparisonRow> rows = new ArrayList<>();
        Set<String> matchedOlderNames = new HashSet<>();

        for (int i = 0; i < newerCriteria.size(); i++) {
            AbstractReport.Criterion nc = newerCriteria.get(i);
            String name = displayName(nc, i);
            double newerScore = newerScores.getOrDefault(i, 0.0);
            Boolean newerMet = isMet(nc, newerScore, position);

            Integer olderIdx = olderIndexByName.get(normalize(name));
            if (olderIdx != null) {
                matchedOlderNames.add(normalize(name));
                AbstractReport.Criterion oc = olderCriteria.get(olderIdx);
                double olderScore = olderScores.getOrDefault(olderIdx, 0.0);
                Boolean olderMet = isMet(oc, olderScore, position);
                rows.add(new CriterionComparisonRow(
                        name, olderScore, newerScore, newerScore - olderScore, olderMet, newerMet, true, true));
            } else {
                rows.add(new CriterionComparisonRow(name, null, newerScore, null, null, newerMet, false, true));
            }
        }

        // Criteria that exist only in the older report (none for the Info pair today, but a future
        // pair might drop one) — appended after the newer report's own order.
        for (int i = 0; i < olderCriteria.size(); i++) {
            String name = displayName(olderCriteria.get(i), i);
            if (matchedOlderNames.contains(normalize(name))) {
                continue;
            }
            AbstractReport.Criterion oc = olderCriteria.get(i);
            double olderScore = olderScores.getOrDefault(i, 0.0);
            Boolean olderMet = isMet(oc, olderScore, position);
            rows.add(new CriterionComparisonRow(name, olderScore, null, null, olderMet, null, true, false));
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
