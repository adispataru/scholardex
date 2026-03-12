package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.*;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupReportFacade {
    private static final long REFRESH_SLOW_WARN_THRESHOLD_MS = 5_000L;

    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    private final ScopusProjectionReadService scopusProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final GroupIndividualReportRunRepository groupIndividualReportRunRepository;

    public Optional<GroupPublicationsViewModel> buildGroupPublicationsView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return Optional.empty();
        }

        List<Researcher> researchers = new ArrayList<>(group.getResearchers());
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> lookupKeys = new ArrayList<>();
        for (Researcher researcher : researchers) {
            lookupKeys.addAll(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        }
        List<String> authorIds = scopusProjectionReadService.findAuthorsByIdIn(lookupKeys).stream()
                .map(Author::getId)
                .distinct()
                .toList();
        Map<String, Publication> publicationsById = new LinkedHashMap<>();
        scopusProjectionReadService.findAllPublicationsByAuthorsIn(authorIds)
                .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication));
        List<Publication> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });

        List<Author> byIdIn = scopusProjectionReadService.findAuthorsByIdIn(authorKeys);
        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, Forum> forumMap = new HashMap<>();
        List<Forum> forums = scopusProjectionReadService.findForumsByIdIn(forumKeys);
        forums.forEach(f -> forumMap.put(f.getId(), f));

        Map<Integer, List<Publication>> publicationsByYear = publications.stream()
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

        List<IndividualReport> all = individualReportRepository.findAll();

        return Optional.of(new GroupPublicationsViewModel(
                group,
                researchers,
                publications,
                authorMap,
                forumMap,
                publicationsByYear,
                publicationsCountByYear,
                all
        ));
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
        ComputeGroupRunResult computeResult = computeGroupRun(group, reportOpt.get());
        long computeMs = nanosToMs(System.nanoTime() - computeStartNanos);

        long saveStartNanos = System.nanoTime();
        GroupIndividualReportRun run = groupIndividualReportRunRepository.save(computeResult.run());
        long saveMs = nanosToMs(System.nanoTime() - saveStartNanos);

        long totalMs = nanosToMs(System.nanoTime() - refreshStartNanos);
        String refreshTimingMessage = String.format(
                Locale.ROOT,
                "Group report refresh timings: groupId=%s reportId=%s researchers=%d status=%s errors=%d timingsMs[lookup=%d, compute=%d, save=%d, total=%d] computeMs[authorLookup=%d, publicationLoad=%d, activityLoad=%d, citationLoad=%d, scoring=%d, thresholdBuild=%d]",
                groupId,
                reportId,
                group.getResearchers() == null ? 0 : group.getResearchers().size(),
                run.getStatus(),
                run.getBuildErrors() == null ? 0 : run.getBuildErrors().size(),
                lookupMs,
                computeMs,
                saveMs,
                totalMs,
                computeResult.timings().authorLookupMs(),
                computeResult.timings().publicationLoadMs(),
                computeResult.timings().activityLoadMs(),
                computeResult.timings().citationLoadMs(),
                computeResult.timings().scoringMs(),
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
        ComputeGroupRunResult computeResult = computeGroupRun(group, report);
        return groupIndividualReportRunRepository.save(computeResult.run());
    }

    private ComputeGroupRunResult computeGroupRun(Group group, IndividualReport report) {
        List<Researcher> researchers = new ArrayList<>(group.getResearchers());
        researchers.sort(Comparator.comparing(Researcher::getName));

        Map<String, Map<Integer, Double>> researcherScores = new HashMap<>();
        List<String> errors = new ArrayList<>();
        ComputeTimingsAccumulator timings = new ComputeTimingsAccumulator();

        for (Researcher researcher : researchers) {
            long authorLookupStartNanos = System.nanoTime();
            List<Author> authors = scopusProjectionReadService.findAuthorsByIdIn(
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

            long publicationLoadStartNanos = System.nanoTime();
            List<String> authorIds = authors.stream().map(Author::getId).toList();
            List<Publication> publications = scopusProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
            if (!"ANY".equals(report.getIndividualAffiliation().getName())) {
                publications = publications.stream()
                        .filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream()
                                .anyMatch(aff -> p.getAffiliations().contains(aff.getAfid())))
                        .collect(Collectors.toList());
            }
            timings.publicationLoadNanos += (System.nanoTime() - publicationLoadStartNanos);

            List<ActivityInstance> activities = List.of();
            if (hasActivityIndicators) {
                long activityLoadStartNanos = System.nanoTime();
                activities = activityInstanceRepository.findAllByResearcherId(researcher.getId());
                timings.activityLoadNanos += (System.nanoTime() - activityLoadStartNanos);
            }

            CitationContext citationContext = CitationContext.empty();
            if (hasCitationIndicators) {
                long citationLoadStartNanos = System.nanoTime();
                citationContext = prepareCitationContext(publications);
                timings.citationLoadNanos += (System.nanoTime() - citationLoadStartNanos);
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
                }
                if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                    indicatorScore = calculatePublicationScore(indicator, authors, publications);
                } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS)
                        || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                    indicatorScore = calculateCitationScore(indicator, authors, publications, citationContext);
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
        List<Researcher> researchers = new ArrayList<>(group.getResearchers());
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

    private double calculatePublicationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        return scores.get("total").getAuthorScore();
    }

    private double calculateCitationScore(
            Indicator indicator,
            List<Author> authors,
            List<Publication> publications,
            CitationContext citationContext
    ) {
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        Map<String, Map<String, Score>> scores = new HashMap<>();

        for (Publication pub : publications) {
            List<Publication> citations = new ArrayList<>();

            for (Citation cit : citationContext.allCitations()) {
                if (cit.getCitedId().equals(pub.getId())) {
                    if (citationContext.citingPublicationsById().get(cit.getCitingId()) != null) {
                        Publication citing = citationContext.citingPublicationsById().get(cit.getCitingId());
                        if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                            continue;
                        }
                        citations.add(citing);
                    }
                }
            }

            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(pub, citations, indicator);
            scores.put(pub.getTitle(), citScores);
        }

        applyFinalSelector(indicator, scores);
        return scores.values().stream().map(value -> {
            double t = 0.0;
            value.remove("total");
            for (Score score : value.values()) {
                t += score.getAuthorScore();
            }
            return t;
        }).reduce(0.0, Double::sum);
    }

    private void applyFinalSelector(Indicator indicator, Map<String, Map<String, Score>> scores) {
        if (indicator.getSelector() != null && indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
            Map<String, Score> topScores = new HashMap<>();
            scores.forEach((k, v) -> topScores.putAll(v));
            List<String> top10 = topScores.entrySet().stream()
                    .filter(x -> !x.getKey().equals("total"))
                    .sorted(Map.Entry.<String, Score>comparingByValue(Comparator.comparingDouble(Score::getAuthorScore)).reversed())
                    .limit(10)
                    .map(Map.Entry::getKey)
                    .toList();
            boolean[] used = new boolean[top10.size()];
            for (String key : scores.keySet()) {
                Iterator<String> titleIterator = scores.get(key).keySet().iterator();

                while (titleIterator.hasNext()) {
                    String title = titleIterator.next();
                    if (title.equals("total")) {
                        continue;
                    }
                    if (!top10.contains(title) || used[top10.indexOf(title)]) {
                        titleIterator.remove();
                    }
                    if (top10.contains(title)) {
                        used[top10.indexOf(title)] = true;
                    }
                }
                double totalA = 0.0;
                double totalF = 0.0;
                scores.get(key).remove("total");
                for (Score score : scores.get(key).values()) {
                    totalA += score.getAuthorScore();
                    totalF += score.getScore();
                }
                Score score = new Score();
                score.setScore(totalF);
                score.setAuthorScore(totalA);
                scores.get(key).put("total", score);
            }
        }
    }

    private String researcherDisplayName(Researcher researcher) {
        String first = researcher.getFirstName() == null ? "" : researcher.getFirstName().trim();
        String last = researcher.getLastName() == null ? "" : researcher.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isBlank() ? researcher.getId() : full;
    }

    private CitationContext prepareCitationContext(List<Publication> publications) {
        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scopusProjectionReadService.findAllCitationsByCitedIdIn(pubIds);
        List<String> citationIds = allCitations.stream().map(Citation::getCitingId).toList();
        List<Publication> citingPublications = scopusProjectionReadService.findAllPublicationsByIdIn(citationIds);
        Map<String, Publication> citingPublicationsById = new HashMap<>();
        for (Publication publication : citingPublications) {
            if (publication != null && publication.getId() != null) {
                citingPublicationsById.putIfAbsent(publication.getId(), publication);
            }
        }
        return new CitationContext(allCitations, citingPublicationsById);
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
            long scoringMs,
            long thresholdBuildMs
    ) {
    }

    private record CitationContext(
            List<Citation> allCitations,
            Map<String, Publication> citingPublicationsById
    ) {
        static CitationContext empty() {
            return new CitationContext(List.of(), Map.of());
        }
    }

    private static class ComputeTimingsAccumulator {
        private long authorLookupNanos;
        private long publicationLoadNanos;
        private long activityLoadNanos;
        private long citationLoadNanos;
        private long scoringNanos;
        private long thresholdBuildNanos;

        private ComputeTimingsSummary toSummary() {
            return new ComputeTimingsSummary(
                    Math.max(0L, authorLookupNanos / 1_000_000L),
                    Math.max(0L, publicationLoadNanos / 1_000_000L),
                    Math.max(0L, activityLoadNanos / 1_000_000L),
                    Math.max(0L, citationLoadNanos / 1_000_000L),
                    Math.max(0L, scoringNanos / 1_000_000L),
                    Math.max(0L, thresholdBuildNanos / 1_000_000L)
            );
        }
    }

}
