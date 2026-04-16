package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.*;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;
import ro.uvt.pokedex.core.service.reporting.PublicationSubtypeSupport;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupReportFacade {
    private static final long REFRESH_SLOW_WARN_THRESHOLD_MS = 5_000L;
    private static final List<String> VENUE_CLASS_BUCKET_ORDER = List.of(
            "Q1", "Q2", "Q3", "Q4", "A_STAR", "A", "B", "C", "D", "LNCS", "BOOK_LNCS", "SCOPUS", "NON_RANK", "Unranked"
    );

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    private final CNFISScoringService2025 cnfisScoringService2025;
    private final ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final GroupIndividualReportRunRepository groupIndividualReportRunRepository;
    private final ReportingLookupMemoization reportingLookupMemoization;

    public Optional<GroupPublicationsViewModel> buildGroupPublicationsView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return Optional.empty();
        }

        List<Researcher> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> lookupKeys = new ArrayList<>();
        for (Researcher researcher : researchers) {
            lookupKeys.addAll(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        }
        List<String> authorIds = scholardexProjectionReadService.findAuthorsByIdIn(lookupKeys).stream()
                .map(ScholardexAuthorView::getId)
                .distinct()
                .toList();
        Map<String, ScholardexPublicationView> publicationsById = new LinkedHashMap<>();
        scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds)
                .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication));
        List<ScholardexPublicationView> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });

        List<ScholardexAuthorView> byIdIn = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys);
        Map<String, ScholardexAuthorView> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        List<ScholardexForumView> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);
        forums.forEach(f -> forumMap.put(f.getId(), f));

        Map<Integer, List<ScholardexPublicationView>> publicationsByYear = publications.stream()
                .map(publication -> new AbstractMap.SimpleEntry<>(
                        publication,
                        PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.groupingBy(
                        entry -> entry.getValue().get(),
                        TreeMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));
        publicationsByYear.values().forEach(PublicationOrderingSupport::sortPublicationsInPlace);

        Map<Integer, Long> publicationsCountByYear = publications.stream()
                .map(publication -> PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.groupingBy(year -> year, TreeMap::new, Collectors.counting()));

        Map<Integer, Map<String, Long>> venueClassCountByYear = new TreeMap<>();
        publications.forEach(publication -> PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                .ifPresent(year -> {
                    String bucket = classifyVenueBucket(publication, forumMap.get(publication.getForum()));
                    venueClassCountByYear
                            .computeIfAbsent(year, ignored -> new LinkedHashMap<>(initializeVenueClassBuckets()))
                            .merge(bucket, 1L, Long::sum);
                }));

        List<IndividualReport> all = individualReportRepository.findAll();

        return Optional.of(new GroupPublicationsViewModel(
                group,
                researchers,
                publications,
                authorMap,
                forumMap,
                publicationsByYear,
                publicationsCountByYear,
                venueClassCountByYear,
                all
        ));
    }

    private Map<String, Long> initializeVenueClassBuckets() {
        Map<String, Long> buckets = new LinkedHashMap<>();
        VENUE_CLASS_BUCKET_ORDER.forEach(bucket -> buckets.put(bucket, 0L));
        return buckets;
    }

    private String classifyVenueBucket(ScholardexPublicationView publication, ScholardexForumView forum) {
        if (PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "ar", "re")) {
            return classifyJournalBucket(publication);
        }
        if (isLncsBookChapter(publication, forum)) {
            return classifyLncsBookChapterBucket(publication, forum);
        }
        if (PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "cp")) {
            return classifyConferenceBucket(publication);
        }
        return "Unranked";
    }

    private boolean isLncsBookChapter(ScholardexPublicationView publication, ScholardexForumView forum) {
        if (!PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "ch")) {
            return false;
        }
        String publicationName = forum == null || forum.getPublicationName() == null
                ? ""
                : forum.getPublicationName().trim();
        return publicationName.contains("Lecture Notes in ")
                || publicationName.contains("Lecture Notes on ");
    }

    private String classifyLncsBookChapterBucket(ScholardexPublicationView publication, ScholardexForumView forum) {
        int year = PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                .orElse(2023);
        Optional<Score> conferenceScore = computerScienceConferenceScoringService.tryResolveCoreScore(
                publication.toScoringPublication(),
                forum,
                year
        );
        if (conferenceScore.isPresent()) {
            Score score = conferenceScore.get();
            if (score.getCoreRankingEquivalent() != null && !score.getCoreRankingEquivalent().isBlank()) {
                return score.getCoreRankingEquivalent().trim();
            }
        }
        return "BOOK_LNCS";
    }

    private String classifyJournalBucket(ScholardexPublicationView publication) {
        Domain domain = new Domain();
        domain.setName("ALL");
        CNFISReport2025 report = cnfisScoringService2025.getReport(publication.toScoringPublication(), domain);
        if (report.isIsiQ1()) {
            return "Q1";
        }
        if (report.isIsiQ2()) {
            return "Q2";
        }
        if (report.isIsiQ3()) {
            return "Q3";
        }
        if (report.isIsiQ4()) {
            return "Q4";
        }
        return "Unranked";
    }

    private String classifyConferenceBucket(ScholardexPublicationView publication) {
        Score score = computerScienceConferenceScoringService.getScore(publication.toScoringPublication(), venueClassificationIndicator());
        if ("LNCS".equalsIgnoreCase(score.getQuarter())) {
            return "LNCS";
        }
        if ("SCOPUS".equalsIgnoreCase(score.getQuarter())) {
            return "SCOPUS";
        }
        if (score.getCoreRankingEquivalent() == null || score.getCoreRankingEquivalent().isBlank()) {
            return "Unranked";
        }
        return score.getCoreRankingEquivalent().trim();
    }

    private Indicator venueClassificationIndicator() {
        Indicator indicator = new Indicator();
        Domain domain = new Domain();
        domain.setName("ALL");
        indicator.setDomain(domain);
        return indicator;
    }

    public GroupIndividualReportViewModel buildGroupIndividualReportView(String groupId, String reportId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
        }

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
        }
        IndividualReport report = reportOpt.get();

        GroupIndividualReportRun run = groupIndividualReportRunRepository
                .findTopByGroupIdAndReportDefinitionIdOrderByCreatedAtDesc(groupId, reportId)
                .orElseGet(() -> computeAndPersistGroupRun(group, report));

        return toViewModel(group, report, run);
    }

    public GroupIndividualReportViewModel refreshGroupIndividualReportView(String groupId, String reportId) {
        long refreshStartNanos = System.nanoTime();
        long lookupStartNanos = System.nanoTime();
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
        }

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
        }
        long lookupMs = nanosToMs(System.nanoTime() - lookupStartNanos);

        long computeStartNanos = System.nanoTime();
        ComputeGroupRunResult computeResult = reportingLookupMemoization.withRefreshScope(
                () -> computeGroupRun(group, reportOpt.get())
        );
        long computeMs = nanosToMs(System.nanoTime() - computeStartNanos);

        long saveStartNanos = System.nanoTime();
        GroupIndividualReportRun run = groupIndividualReportRunRepository.save(computeResult.run());
        long saveMs = nanosToMs(System.nanoTime() - saveStartNanos);

        long totalMs = nanosToMs(System.nanoTime() - refreshStartNanos);
        String activeReadStore = "POSTGRES";
        String refreshTimingMessage = String.format(
                Locale.ROOT,
                "Group report refresh timings: groupId=%s reportId=%s readStore=%s researchers=%d status=%s errors=%d counts[publications=%d, citationFacts=%d, citationIndicators=%d, activityIndicators=%d] timingsMs[lookup=%d, compute=%d, save=%d, total=%d] computeMs[authorLookup=%d, publicationLoad=%d, activityLoad=%d, citationLoad=%d, citationBasePrecompute=%d, scoring=%d, publicationScoring=%d, activityScoring=%d, citationScoring=%d, selector=%d, thresholdBuild=%d]",
                groupId,
                reportId,
                activeReadStore,
                group.getMemberIds() == null ? 0 : group.getMemberIds().size(),
                run.getStatus(),
                run.getBuildErrors() == null ? 0 : run.getBuildErrors().size(),
                computeResult.timings().publicationsProcessed(),
                computeResult.timings().citationFacts(),
                computeResult.timings().citationIndicators(),
                computeResult.timings().activityIndicators(),
                lookupMs,
                computeMs,
                saveMs,
                totalMs,
                computeResult.timings().authorLookupMs(),
                computeResult.timings().publicationLoadMs(),
                computeResult.timings().activityLoadMs(),
                computeResult.timings().citationLoadMs(),
                computeResult.timings().citationBasePrecomputeMs(),
                computeResult.timings().scoringMs(),
                computeResult.timings().publicationScoringMs(),
                computeResult.timings().activityScoringMs(),
                computeResult.timings().citationScoringMs(),
                computeResult.timings().selectorMs(),
                computeResult.timings().thresholdBuildMs()
        );
        if (totalMs > REFRESH_SLOW_WARN_THRESHOLD_MS) {
            log.warn(refreshTimingMessage);
        } else if (log.isDebugEnabled()) {
            log.debug(refreshTimingMessage);
        }

        return toViewModel(group, reportOpt.get(), run);
    }

    private GroupIndividualReportRun computeAndPersistGroupRun(Group group, IndividualReport report) {
        ComputeGroupRunResult computeResult = reportingLookupMemoization.withRefreshScope(
                () -> computeGroupRun(group, report)
        );
        return groupIndividualReportRunRepository.save(computeResult.run());
    }

    private ComputeGroupRunResult computeGroupRun(Group group, IndividualReport report) {
        List<Researcher> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(Researcher::getName));

        Map<String, Map<Integer, Double>> researcherScores = new HashMap<>();
        List<String> errors = new ArrayList<>();
        ComputeTimingsAccumulator timings = new ComputeTimingsAccumulator();

        for (Researcher researcher : researchers) {
            long authorLookupStartNanos = System.nanoTime();
            List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                    researcherAuthorLookupService.resolveAuthorLookupKeys(researcher)
            );
            timings.authorLookupNanos += (System.nanoTime() - authorLookupStartNanos);
            if (authors.isEmpty()) {
                errors.add("No authors found for researcher " + researcherDisplayName(researcher));
                continue;
            }

            List<Indicator> indicators = report.getIndicators() == null ? List.of() : report.getIndicators();
            boolean hasActivityIndicators = indicators.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(indicator -> indicator.getOutputType() != null
                            && indicator.getOutputType().toString().contains("ACTIVIT"));
            boolean hasCitationIndicators = indicators.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(indicator -> Indicator.Type.CITATIONS.equals(indicator.getOutputType())
                            || Indicator.Type.CITATIONS_EXCLUDE_SELF.equals(indicator.getOutputType()));
            long activityIndicatorCount = indicators.stream()
                    .filter(Objects::nonNull)
                    .filter(indicator -> indicator.getOutputType() != null
                            && indicator.getOutputType().toString().contains("ACTIVIT"))
                    .count();
            long citationIndicatorCount = indicators.stream()
                    .filter(Objects::nonNull)
                    .filter(indicator -> Indicator.Type.CITATIONS.equals(indicator.getOutputType())
                            || Indicator.Type.CITATIONS_EXCLUDE_SELF.equals(indicator.getOutputType()))
                    .count();
            timings.activityIndicators += activityIndicatorCount;
            timings.citationIndicators += citationIndicatorCount;

            long publicationLoadStartNanos = System.nanoTime();
            List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
            List<ScholardexPublicationView> publications = scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
            if (report.getIndividualAffiliation() != null
                    && !"ANY".equals(report.getIndividualAffiliation().getName())) {
                publications = publications.stream()
                        .filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream()
                                .anyMatch(aff -> p.getAffiliations().contains(aff.getAfid())))
                        .collect(Collectors.toList());
            }
            timings.publicationLoadNanos += (System.nanoTime() - publicationLoadStartNanos);
            timings.publicationsProcessed += publications.size();
            Set<String> researcherAuthorIds = authors.stream()
                    .map(ScholardexAuthorView::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<ActivityInstance> activities = List.of();
            if (hasActivityIndicators) {
                long activityLoadStartNanos = System.nanoTime();
                activities = activityInstanceRepository.findAllByResearcherId(researcher.getId());
                timings.activityLoadNanos += (System.nanoTime() - activityLoadStartNanos);
            }

            ReportScopedIndicatorScoringSupport.CitationContext citationContext =
                    ReportScopedIndicatorScoringSupport.CitationContext.empty();
            Map<Indicator, Map<String, Score>> citationBaseScoresByIndicator = Map.of();
            if (hasCitationIndicators) {
                long citationLoadStartNanos = System.nanoTime();
                citationContext = ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService);
                timings.citationLoadNanos += (System.nanoTime() - citationLoadStartNanos);
                timings.citationFacts += citationContext.citationFactsCount();
                long citationBasePrecomputeStartNanos = System.nanoTime();
                citationBaseScoresByIndicator = ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                        indicators,
                        citationContext,
                        scientificProductionService
                );
                timings.citationBasePrecomputeNanos += (System.nanoTime() - citationBasePrecomputeStartNanos);
            }

            Map<Indicator, Double> indicatorScores = new HashMap<>();

            for (Indicator indicator : indicators) {
                if (indicator == null) {
                    errors.add("Null indicator in report " + report.getId());
                    continue;
                }

                long indicatorScoringStartNanos = System.nanoTime();
                double indicatorScore = 0;
                if (indicator.getOutputType().toString().contains("ACTIVIT")) {
                    List<ActivityInstance> filteredActivities = activities.stream()
                            .filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName()))
                            .toList();
                    indicatorScore = activityReportingService.calculateActivityScores(filteredActivities, indicator)
                            .get("total")
                            .getAuthorScore();
                    timings.activityScoringNanos += (System.nanoTime() - indicatorScoringStartNanos);
                }
                if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                    indicatorScore = calculatePublicationScore(indicator, authors, publications);
                    timings.publicationScoringNanos += (System.nanoTime() - indicatorScoringStartNanos);
                } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS)
                        || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                    ReportScopedIndicatorScoringSupport.CitationScoreResult citationScoreResult =
                            ReportScopedIndicatorScoringSupport.calculateCitationScore(
                                    indicator,
                                    publications,
                                    researcherAuthorIds,
                                    citationContext,
                                    citationBaseScoresByIndicator.getOrDefault(indicator, Map.of()),
                                    scientificProductionService
                            );
                    indicatorScore = citationScoreResult.score();
                    timings.selectorNanos += citationScoreResult.selectorNanos();
                    timings.citationScoringNanos += (System.nanoTime() - indicatorScoringStartNanos);
                }

                indicatorScores.put(indicator, indicatorScore);
                timings.scoringNanos += (System.nanoTime() - indicatorScoringStartNanos);
            }

            Map<Integer, Double> criterionScores = new HashMap<>();
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion criterion = report.getCriteria().get(i);
                double criterionScore = 0;
                for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                    if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= report.getIndicators().size()) {
                        errors.add("Invalid indicator index " + indicatorIndex + " in criterion " + i);
                        continue;
                    }
                    Indicator ind = report.getIndicators().get(indicatorIndex);
                    if (indicatorScores.containsKey(ind)) {
                        criterionScore += indicatorScores.get(ind);
                    }
                }
                criterionScores.put(i, criterionScore);
            }
            researcherScores.put(researcher.getId(), criterionScores);
        }

        long thresholdBuildStartNanos = System.nanoTime();
        Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();
        for (int i = 0; i < report.getCriteria().size(); i++) {
            AbstractReport.Criterion criterion = report.getCriteria().get(i);
            Map<String, Double> thresholds = new HashMap<>();
            for (AbstractReport.Threshold threshold : criterion.getThresholds()) {
                thresholds.put(threshold.getPosition().name(), threshold.getValue());
            }
            criteriaThresholds.put(i, thresholds);
        }
        timings.thresholdBuildNanos += (System.nanoTime() - thresholdBuildStartNanos);

        GroupIndividualReportRun run = new GroupIndividualReportRun();
        run.setGroupId(group.getId());
        run.setReportDefinitionId(report.getId());
        run.setResearcherScores(researcherScores);
        run.setCriteriaThresholds(criteriaThresholds);
        run.setCreatedAt(java.time.Instant.now());
        run.setBuildErrors(errors);
        if (!errors.isEmpty()) {
            run.setStatus(researcherScores.isEmpty() ? GroupIndividualReportRun.Status.FAILED : GroupIndividualReportRun.Status.PARTIAL);
        } else {
            run.setStatus(GroupIndividualReportRun.Status.READY);
        }
        return new ComputeGroupRunResult(run, timings.toSummary());
    }

    private GroupIndividualReportViewModel toViewModel(Group group, IndividualReport report, GroupIndividualReportRun run) {
        List<Researcher> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(Researcher::getName));

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("report", report);
        attrs.put("group", group);
        attrs.put("researchers", researchers);
        attrs.put("researcherScores", run.getResearcherScores() == null ? Map.of() : run.getResearcherScores());
        attrs.put("criteriaThresholds", run.getCriteriaThresholds() == null ? Map.of() : run.getCriteriaThresholds());
        attrs.put("runCreatedAt", run.getCreatedAt());
        attrs.put("runStatus", run.getStatus());
        attrs.put("runBuildErrors", run.getBuildErrors() == null ? List.of() : run.getBuildErrors());
        return new GroupIndividualReportViewModel(null, attrs);
    }

    private double calculatePublicationScore(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications) {
        return ReportingComputationSupport.calculatePublicationScore(indicator, authors, publications, scientificProductionService);
    }

    private String researcherDisplayName(Researcher researcher) {
        String first = researcher.getFirstName() == null ? "" : researcher.getFirstName().trim();
        String last = researcher.getLastName() == null ? "" : researcher.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isBlank() ? researcher.getId() : full;
    }

    /**
     * Loads the group's member users by their email ids and converts each to a
     * {@link Researcher} value-object. Falls back to the legacy {@code @DBRef}
     * list when {@code memberIds} is empty (e.g. before migration).
     */
    private List<Researcher> loadResearchers(Group group) {
        List<String> memberIds = group.getMemberIds();
        if (memberIds != null && !memberIds.isEmpty()) {
            return userRepository.findAllById(memberIds).stream()
                    .map(Researcher::fromUser)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        // Legacy fallback — resolved via DBRef while migration is in progress
        return group.getResearchers() != null
                ? new ArrayList<>(group.getResearchers())
                : new ArrayList<>();
    }

    private long nanosToMs(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private record ComputeGroupRunResult(
            GroupIndividualReportRun run,
            ComputeTimingsSummary timings
    ) {
    }

    private record ComputeTimingsSummary(
            long authorLookupMs,
            long publicationLoadMs,
            long activityLoadMs,
            long citationLoadMs,
            long citationBasePrecomputeMs,
            long scoringMs,
            long publicationScoringMs,
            long activityScoringMs,
            long citationScoringMs,
            long selectorMs,
            long publicationsProcessed,
            long citationFacts,
            long citationIndicators,
            long activityIndicators,
            long thresholdBuildMs
    ) {
    }

    private static class ComputeTimingsAccumulator {
        private long authorLookupNanos;
        private long publicationLoadNanos;
        private long activityLoadNanos;
        private long citationLoadNanos;
        private long citationBasePrecomputeNanos;
        private long scoringNanos;
        private long publicationScoringNanos;
        private long activityScoringNanos;
        private long citationScoringNanos;
        private long selectorNanos;
        private long publicationsProcessed;
        private long citationFacts;
        private long citationIndicators;
        private long activityIndicators;
        private long thresholdBuildNanos;

        private ComputeTimingsSummary toSummary() {
            return new ComputeTimingsSummary(
                    Math.max(0L, authorLookupNanos / 1_000_000L),
                    Math.max(0L, publicationLoadNanos / 1_000_000L),
                    Math.max(0L, activityLoadNanos / 1_000_000L),
                    Math.max(0L, citationLoadNanos / 1_000_000L),
                    Math.max(0L, citationBasePrecomputeNanos / 1_000_000L),
                    Math.max(0L, scoringNanos / 1_000_000L),
                    Math.max(0L, publicationScoringNanos / 1_000_000L),
                    Math.max(0L, activityScoringNanos / 1_000_000L),
                    Math.max(0L, citationScoringNanos / 1_000_000L),
                    Math.max(0L, selectorNanos / 1_000_000L),
                    Math.max(0L, publicationsProcessed),
                    Math.max(0L, citationFacts),
                    Math.max(0L, citationIndicators),
                    Math.max(0L, activityIndicators),
                    Math.max(0L, thresholdBuildNanos / 1_000_000L)
            );
        }
    }

}
