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
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
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
    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusForumRepository scopusForumRepository;
    private final ScopusCitationRepository scopusCitationRepository;

    public Optional<GroupPublicationsViewModel> buildGroupPublicationsView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return Optional.empty();
        }

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> authorIds = new ArrayList<>();
        for (Researcher researcher : researchers) {
            authorIds.addAll(researcher.getScopusId());
        }
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });

        List<Author> byIdIn = scopusAuthorRepository.findByIdIn(authorKeys);
        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, Forum> forumMap = new HashMap<>();
        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
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

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
        }

        IndividualReport report = reportOpt.get();
        Map<String, Map<Integer, Double>> researcherScores = new HashMap<>();
        for (Researcher researcher : researchers) {
            List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
            if (authors.isEmpty()) {
                continue;
            }

            List<String> authorIds = authors.stream().map(Author::getId).toList();
            List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
            if (!"ANY".equals(report.getIndividualAffiliation().getName())) {
                publications = publications.stream().filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream().anyMatch(aff -> p.getAffiliations().contains(aff.getAfid()))).collect(Collectors.toList());
            }

            List<Indicator> indicators = report.getIndicators();
            Map<Indicator, Double> indicatorScores = new HashMap<>();

            for (Indicator indicator : indicators) {
                double indicatorScore = 0;
                if (indicator.getOutputType().toString().contains("ACTIVIT")) {
                    List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcher.getId());
                    activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
                    indicatorScore = activityReportingService.calculateActivityScores(activities, indicator).get("total").getAuthorScore();
                }
                if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                    indicatorScore = calculatePublicationScore(indicator, authors, publications);
                } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                    indicatorScore = calculateCitationScore(indicator, authors, publications);
                }

                indicatorScores.put(indicator, indicatorScore);
            }

            Map<Integer, Double> criterionScores = new HashMap<>();
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion criterion = report.getCriteria().get(i);
                double criterionScore = 0;
                for (Integer in : criterion.getIndicatorIndices()) {
                    Indicator ind = report.getIndicators().get(in);
                    if (indicatorScores.containsKey(ind)) {
                        criterionScore += indicatorScores.get(ind);
                    }
                }
                criterionScores.put(i, criterionScore);
            }
            researcherScores.put(researcher.getId(), criterionScores);
        }

        Map<Integer, Map<Position, Double>> criteriaThresholds = new HashMap<>();
        for (int i = 0; i < report.getCriteria().size(); i++) {
            AbstractReport.Criterion criterion = report.getCriteria().get(i);
            Map<Position, Double> thresholds = new HashMap<>();
            for (AbstractReport.Threshold threshold : criterion.getThresholds()) {
                thresholds.put(threshold.getPosition(), threshold.getValue());
            }
            criteriaThresholds.put(i, thresholds);
        }

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("report", report);
        attrs.put("group", group);
        attrs.put("researchers", researchers);
        attrs.put("researcherScores", researcherScores);
        attrs.put("criteriaThresholds", criteriaThresholds);

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

    private double calculateCitationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);

        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scopusCitationRepository.findAllByCitedIdIn(pubIds);
        List<String> citationIds = allCitations.stream().map(Citation::getCitingId).toList();
        List<Publication> allCitationsPub = scopusPublicationRepository.findAllByIdIn(citationIds);
        Map<String, List<Publication>> pubCitationsMap = allCitationsPub.stream().collect(Collectors.groupingBy(Publication::getId));
        Map<String, Map<String, Score>> scores = new HashMap<>();

        for (Publication pub : publications) {
            List<Publication> citations = new ArrayList<>();

            for (Citation cit : allCitations) {
                if (cit.getCitedId().equals(pub.getId())) {
                    if (pubCitationsMap.get(cit.getCitingId()) != null) {
                        Publication citing = pubCitationsMap.get(cit.getCitingId()).getFirst();
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
}
