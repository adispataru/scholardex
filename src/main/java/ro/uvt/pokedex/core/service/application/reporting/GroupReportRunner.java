package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.GroupMembershipService;
import ro.uvt.pokedex.core.service.application.ReportScopedIndicatorScoringSupport;
import ro.uvt.pokedex.core.service.application.ReportingComputationSupport;
import ro.uvt.pokedex.core.service.application.ReportingLookupMemoization;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the per-researcher score computation for a group individual-report run, including
 * timing collection. Stateless; safe to share. Persisting the result is the caller's job
 * (so the timing of the save step stays in the orchestrator).
 */
@Component
@RequiredArgsConstructor
public class GroupReportRunner {

    private final UserRepository userRepository;
    private final GroupMembershipService groupMembershipService;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScientificProductionService scientificProductionService;
    private final ro.uvt.pokedex.core.service.reporting.ActivityReportingService activityReportingService;
    private final ReportingLookupMemoization reportingLookupMemoization;
    private final GroupIndividualReportRunRepository groupIndividualReportRunRepository;

    public ComputeResult computeAndPersist(Group group, IndividualReport report) {
        ComputeResult result = compute(group, report);
        GroupIndividualReportRun saved = groupIndividualReportRunRepository.save(result.run());
        return new ComputeResult(saved, result.timings());
    }

    public ComputeResult compute(Group group, IndividualReport report) {
        return reportingLookupMemoization.withRefreshScope(() -> computeInternal(group, report));
    }

    public GroupIndividualReportRun save(GroupIndividualReportRun run) {
        return groupIndividualReportRunRepository.save(run);
    }

    private ComputeResult computeInternal(Group group, IndividualReport report) {
        List<User> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));

        List<GroupIndividualReportRun.ResearcherScore> researcherScores = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Timings timings = new Timings();

        for (User user : researchers) {
            long authorLookupStart = System.nanoTime();
            List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                    researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
            timings.authorLookupNanos += System.nanoTime() - authorLookupStart;
            if (authors.isEmpty()) {
                errors.add("No authors found for member " + memberDisplayName(user));
                continue;
            }

            List<Indicator> indicators = report.getIndicators() == null ? List.of() : report.getIndicators();
            boolean hasActivity = indicators.stream().filter(Objects::nonNull)
                    .anyMatch(ReportingComputationSupport::isActivityIndicator);
            boolean hasCitation = indicators.stream().filter(Objects::nonNull)
                    .anyMatch(ReportingComputationSupport::isCitationIndicator);
            timings.activityIndicators += indicators.stream().filter(Objects::nonNull)
                    .filter(ReportingComputationSupport::isActivityIndicator).count();
            timings.citationIndicators += indicators.stream().filter(Objects::nonNull)
                    .filter(ReportingComputationSupport::isCitationIndicator).count();

            long publicationLoadStart = System.nanoTime();
            List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
            List<ScholardexPublicationView> publications = applyAffiliationFilter(report,
                    scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds));
            timings.publicationLoadNanos += System.nanoTime() - publicationLoadStart;
            timings.publicationsProcessed += publications.size();
            Set<String> researcherAuthorIds = authors.stream()
                    .map(ScholardexAuthorView::getId).filter(Objects::nonNull).collect(Collectors.toSet());

            List<ActivityInstance> activities = List.of();
            if (hasActivity) {
                long activityLoadStart = System.nanoTime();
                activities = activityInstanceRepository.findAllByResearcherId(user.getEmail());
                timings.activityLoadNanos += System.nanoTime() - activityLoadStart;
            }

            CitationPrecompute citationPrecompute = CitationPrecompute.empty();
            if (hasCitation) {
                long citationLoadStart = System.nanoTime();
                ReportScopedIndicatorScoringSupport.CitationContext citationContext =
                        ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService);
                timings.citationLoadNanos += System.nanoTime() - citationLoadStart;
                timings.citationFacts += citationContext.citationFactsCount();
                long precomputeStart = System.nanoTime();
                Map<Indicator, Map<String, Score>> baseScores =
                        ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                                indicators, citationContext, scientificProductionService);
                timings.citationBasePrecomputeNanos += System.nanoTime() - precomputeStart;
                citationPrecompute = new CitationPrecompute(citationContext, baseScores);
            }

            Map<Indicator, Double> indicatorScores = new HashMap<>();
            for (Indicator indicator : indicators) {
                if (indicator == null) {
                    errors.add("Null indicator in report " + report.getId());
                    continue;
                }
                long indicatorStart = System.nanoTime();
                double indicatorScore = 0;
                if (ReportingComputationSupport.isActivityIndicator(indicator)) {
                    List<ActivityInstance> filtered = activities.stream()
                            .filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName()))
                            .toList();
                    indicatorScore = activityReportingService.calculateActivityScores(filtered, indicator)
                            .get("total").getAuthorScore();
                    timings.activityScoringNanos += System.nanoTime() - indicatorStart;
                }
                if (ReportingComputationSupport.isPublicationIndicator(indicator)) {
                    indicatorScore = ReportingComputationSupport.calculatePublicationScore(
                            indicator, authors, publications, scientificProductionService);
                    timings.publicationScoringNanos += System.nanoTime() - indicatorStart;
                } else if (ReportingComputationSupport.isCitationIndicator(indicator)) {
                    ReportScopedIndicatorScoringSupport.CitationScoreResult citationResult =
                            ReportScopedIndicatorScoringSupport.calculateCitationScore(
                                    indicator, publications, researcherAuthorIds,
                                    citationPrecompute.context(),
                                    citationPrecompute.baseScores().getOrDefault(indicator, Map.of()),
                                    scientificProductionService);
                    indicatorScore = citationResult.score();
                    timings.selectorNanos += citationResult.selectorNanos();
                    timings.citationScoringNanos += System.nanoTime() - indicatorStart;
                }
                indicatorScores.put(indicator, indicatorScore);
                timings.scoringNanos += System.nanoTime() - indicatorStart;
            }

            Map<Integer, Double> criterionScores = computeCriterionScores(report, indicators, indicatorScores, errors);
            researcherScores.add(new GroupIndividualReportRun.ResearcherScore(user.getEmail(), criterionScores));
        }

        long thresholdStart = System.nanoTime();
        Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();
        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            Map<String, Double> thresholds = new HashMap<>();
            for (AbstractReport.Threshold threshold : criteria.get(i).getThresholds()) {
                thresholds.put(threshold.getPosition().name(), threshold.getValue());
            }
            criteriaThresholds.put(i, thresholds);
        }
        timings.thresholdBuildNanos += System.nanoTime() - thresholdStart;

        GroupIndividualReportRun run = new GroupIndividualReportRun();
        run.setGroupId(group.getId());
        run.setReportDefinitionId(report.getId());
        run.setResearcherScores(researcherScores);
        run.setCriteriaThresholds(criteriaThresholds);
        run.setCreatedAt(Instant.now());
        run.setBuildErrors(errors);
        run.setStatus(determineStatus(errors, researcherScores));
        return new ComputeResult(run, timings.toSummary());
    }

    private GroupIndividualReportRun.Status determineStatus(
            List<String> errors, List<GroupIndividualReportRun.ResearcherScore> researcherScores) {
        if (errors.isEmpty()) return GroupIndividualReportRun.Status.READY;
        return researcherScores.isEmpty()
                ? GroupIndividualReportRun.Status.FAILED
                : GroupIndividualReportRun.Status.PARTIAL;
    }

    private Map<Integer, Double> computeCriterionScores(
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
                    if (indicatorScores.containsKey(indicator)) {
                        score += indicatorScores.get(indicator);
                    }
                }
            }
            criterionScores.put(i, score);
        }
        return criterionScores;
    }

    private List<ScholardexPublicationView> applyAffiliationFilter(
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

    private List<User> loadResearchers(Group group) {
        List<String> memberIds = groupMembershipService.listCurrentMemberUserIds(group.getId());
        if (memberIds.isEmpty()) return new ArrayList<>();
        return userRepository.findAllById(memberIds).stream()
                .filter(u -> u.getResearcherProfile() != null)
                .collect(Collectors.toList());
    }

    private String memberDisplayName(User user) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        String first = (profile == null || profile.getFirstName() == null) ? "" : profile.getFirstName().trim();
        String last = (profile == null || profile.getLastName() == null) ? "" : profile.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isBlank() ? user.getEmail() : full;
    }

    public record ComputeResult(GroupIndividualReportRun run, TimingsSummary timings) {}

    public record TimingsSummary(
            long authorLookupMs, long publicationLoadMs, long activityLoadMs,
            long citationLoadMs, long citationBasePrecomputeMs, long scoringMs,
            long publicationScoringMs, long activityScoringMs, long citationScoringMs,
            long selectorMs, long publicationsProcessed, long citationFacts,
            long citationIndicators, long activityIndicators, long thresholdBuildMs) {}

    private record CitationPrecompute(
            ReportScopedIndicatorScoringSupport.CitationContext context,
            Map<Indicator, Map<String, Score>> baseScores) {
        static CitationPrecompute empty() {
            return new CitationPrecompute(
                    ReportScopedIndicatorScoringSupport.CitationContext.empty(), Map.of());
        }
    }

    private static class Timings {
        long authorLookupNanos, publicationLoadNanos, activityLoadNanos, citationLoadNanos;
        long citationBasePrecomputeNanos, scoringNanos, publicationScoringNanos, activityScoringNanos;
        long citationScoringNanos, selectorNanos, thresholdBuildNanos;
        long publicationsProcessed, citationFacts, citationIndicators, activityIndicators;

        TimingsSummary toSummary() {
            return new TimingsSummary(
                    msOf(authorLookupNanos), msOf(publicationLoadNanos), msOf(activityLoadNanos),
                    msOf(citationLoadNanos), msOf(citationBasePrecomputeNanos), msOf(scoringNanos),
                    msOf(publicationScoringNanos), msOf(activityScoringNanos), msOf(citationScoringNanos),
                    msOf(selectorNanos),
                    Math.max(0L, publicationsProcessed), Math.max(0L, citationFacts),
                    Math.max(0L, citationIndicators), Math.max(0L, activityIndicators),
                    msOf(thresholdBuildNanos));
        }

        private static long msOf(long nanos) {
            return Math.max(0L, nanos / 1_000_000L);
        }
    }
}
