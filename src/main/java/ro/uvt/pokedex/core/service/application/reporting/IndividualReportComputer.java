package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.service.application.ReportScopedIndicatorScoringSupport;
import ro.uvt.pokedex.core.service.application.ReportingComputationSupport;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure per-researcher compute for an {@link IndividualReport}. Group / department / division
 * runners all delegate here — the only thing they own is which {@link User}s to feed in and
 * how to package the result.
 */
@Component
@RequiredArgsConstructor
public class IndividualReportComputer {

    private final ActivityInstanceRepository activityInstanceRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScientificProductionService scientificProductionService;
    private final ro.uvt.pokedex.core.service.reporting.ActivityReportingService activityReportingService;

    public Computation compute(List<User> researchers, IndividualReport report) {
        List<ResearcherScoreEntry> researcherScores = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        List<Indicator> indicators = report.getIndicators() == null ? List.of() : report.getIndicators();
        boolean hasActivity = indicators.stream().filter(Objects::nonNull)
                .anyMatch(Indicator::isActivityOutput);
        boolean hasCitation = indicators.stream().filter(Objects::nonNull)
                .anyMatch(Indicator::isCitationsOutput);

        for (User user : researchers) {
            List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                    researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
            if (authors.isEmpty()) {
                errors.add("No authors found for " + memberDisplayName(user));
                continue;
            }

            List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
            List<ScholardexPublicationView> publications = applyAffiliationFilter(report,
                    scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds));
            Set<String> researcherAuthorIds = authors.stream()
                    .map(ScholardexAuthorView::getId).filter(Objects::nonNull).collect(Collectors.toSet());

            List<ActivityInstance> activities = hasActivity
                    ? activityInstanceRepository.findAllByResearcherId(user.getEmail())
                    : List.of();

            ReportScopedIndicatorScoringSupport.CitationContext citationContext = ReportScopedIndicatorScoringSupport.CitationContext.empty();
            Map<Indicator, Map<String, Score>> citationBaseScores = Map.of();
            if (hasCitation) {
                citationContext = ReportScopedIndicatorScoringSupport.prepareCitationContext(
                        publications, scholardexProjectionReadService);
                citationBaseScores = ReportScopedIndicatorScoringSupport
                        .precomputeCitationBaseScoresByIndicator(indicators, citationContext, scientificProductionService);
            }

            Map<Indicator, Double> indicatorScores = new HashMap<>();
            for (Indicator indicator : indicators) {
                if (indicator == null) {
                    errors.add("Null indicator in report " + report.getId());
                    continue;
                }
                double score = 0;
                if (indicator != null && indicator.isActivityOutput()) {
                    List<ActivityInstance> filtered = activities.stream()
                            .filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName()))
                            .toList();
                    score = activityReportingService.calculateActivityScores(filtered, indicator)
                            .get("total").getAuthorScore();
                }
                if (indicator != null && indicator.isPublicationOutput()) {
                    score = ReportingComputationSupport.calculatePublicationScore(
                            indicator, authors, publications, scientificProductionService);
                } else if (indicator != null && indicator.isCitationsOutput()) {
                    ReportScopedIndicatorScoringSupport.CitationScoreResult res =
                            ReportScopedIndicatorScoringSupport.calculateCitationScore(
                                    indicator, publications, researcherAuthorIds,
                                    citationContext,
                                    citationBaseScores.getOrDefault(indicator, Map.of()),
                                    scientificProductionService);
                    score = res.score();
                }
                indicatorScores.put(indicator, score);
            }

            Map<Integer, Double> criterionScores = computeCriterionScores(report, indicators, indicatorScores, errors);
            researcherScores.add(new ResearcherScoreEntry(user.getEmail(), criterionScores));
        }

        Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();
        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            Map<String, Double> thresholds = new HashMap<>();
            for (AbstractReport.Threshold t : criteria.get(i).getThresholds()) {
                thresholds.put(t.getPosition().name(), t.getValue());
            }
            criteriaThresholds.put(i, thresholds);
        }

        return new Computation(researcherScores, errors, criteriaThresholds);
    }

    private static Map<Integer, Double> computeCriterionScores(
            IndividualReport report, List<Indicator> indicators,
            Map<Indicator, Double> indicatorScores, List<String> errors) {
        Map<Integer, Double> criterionScores = new HashMap<>();
        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            AbstractReport.Criterion criterion = criteria.get(i);
            double score = 0.0;
            if (criterion.getIndicatorIndices() != null) {
                for (Integer idx : criterion.getIndicatorIndices()) {
                    if (idx == null || idx < 0 || idx >= indicators.size()) {
                        errors.add("Invalid indicator index " + idx + " in criterion " + i);
                        continue;
                    }
                    Indicator indicator = indicators.get(idx);
                    if (indicatorScores.containsKey(indicator)) score += indicatorScores.get(indicator);
                }
            }
            criterionScores.put(i, score);
        }
        return criterionScores;
    }

    private static List<ScholardexPublicationView> applyAffiliationFilter(
            IndividualReport report, List<ScholardexPublicationView> publications) {
        if (report.getIndividualAffiliation() == null
                || "ANY".equals(report.getIndividualAffiliation().getName())) {
            return publications;
        }
        return publications.stream()
                .filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream()
                        .anyMatch(aff -> p.getAffiliations().contains(aff.getAfid())))
                .toList();
    }

    private static String memberDisplayName(User user) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        String first = (profile == null || profile.getFirstName() == null) ? "" : profile.getFirstName().trim();
        String last = (profile == null || profile.getLastName() == null) ? "" : profile.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isBlank() ? user.getEmail() : full;
    }

    public record Computation(
            List<ResearcherScoreEntry> researcherScores,
            List<String> errors,
            Map<Integer, Map<String, Double>> criteriaThresholds
    ) {}

    public record ResearcherScoreEntry(String userId, Map<Integer, Double> criterionScores) {}
}
