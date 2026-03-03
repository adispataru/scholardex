package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserReportFacade {
    private final UserService userService;
    private final ResearcherService researcherService;
    private final IndicatorRepository indicatorRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusCitationRepository scopusCitationRepository;
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final ScopusForumRepository scopusForumRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;

    public UserIndicatorsViewModel buildIndicatorsView(String userEmail) {
        // userEmail kept in signature to lock facade contract for later permission-aware extensions.
        return new UserIndicatorsViewModel(indicatorRepository.findAll());
    }

    public UserReportsListViewModel buildIndividualReportsListView(String userEmail) {
        // userEmail kept in signature to lock facade contract for future permission-aware filtering.
        return new UserReportsListViewModel(individualReportRepository.findAll());
    }

    public Optional<Indicator> findIndicatorById(String indicatorId) {
        return indicatorRepository.findById(indicatorId);
    }

    public UserIndicatorApplyViewModel buildIndicatorApplyView(String userEmail, String indicatorId) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        User user = userOpt.get();
        String researcherId = user.getResearcherId();
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        Optional<Indicator> indicatorOpt = indicatorRepository.findById(indicatorId);

        if (indicatorOpt.isEmpty() || researcherOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        Indicator indicator = indicatorOpt.get();
        Researcher researcher = researcherOpt.get();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("indicator", indicator);

        if (indicator.getOutputType().toString().contains("ACTIVIT")) {
            List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcherId);
            activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
            return handleActivities(indicator, activities, attrs);
        }

        List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
        List<String> authorIds = authors.stream().map(Author::getId).toList();
        if (authors.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", attrs);
        }

        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
        if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
            return handlePublications(indicator, authors, publications, attrs);
        }
        if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
            return handleCitations(indicator, authors, publications, attrs);
        }

        return new UserIndicatorApplyViewModel("user/indicators", attrs);
    }

    public UserIndividualReportViewModel buildIndividualReportView(String userEmail, String reportId) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return new UserIndividualReportViewModel("redirect:/error", Map.of());
        }
        User currentUser = userOpt.get();

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        Map<String, Object> attrs = new HashMap<>();
        if (reportOpt.isEmpty()) {
            return new UserIndividualReportViewModel(null, attrs);
        }

        IndividualReport report = reportOpt.get();
        attrs.put("report", report);

        Researcher researcher = researcherService.findResearcherById(currentUser.getResearcherId()).orElse(null);
        if (researcher == null) {
            return new UserIndividualReportViewModel("redirect:/error", attrs);
        }

        List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
        if (authors.isEmpty()) {
            return new UserIndividualReportViewModel("redirect:/error", attrs);
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
        if (!"ANY".equals(report.getIndividualAffiliation().getName())) {
            publications = publications.stream().filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream().anyMatch(aff -> p.getAffiliations().contains(aff.getAfid()))).collect(Collectors.toList());
        }

        List<Indicator> indicators = report.getIndicators();
        Map<Indicator, Double> indicatorScores = new HashMap<>();
        double totalScore = 0;

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
            totalScore += indicatorScore;
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

        attrs.put("indicatorScores", indicatorScores);
        attrs.put("criterionScores", criterionScores);
        attrs.put("totalScore", totalScore);

        return new UserIndividualReportViewModel(null, attrs);
    }

    private UserIndicatorApplyViewModel handlePublications(Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Object> attrs) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
        filteredPublications = filteredPublications.stream().filter(p -> scores.containsKey(p.getTitle()) && scores.get(p.getTitle()).getAuthorScore() > 0.0).collect(Collectors.toList());
        attrs.put("publications", filteredPublications);

        Set<String> forumKeys = new HashSet<>();
        filteredPublications.forEach(p -> forumKeys.add(p.getForum()));
        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        attrs.put("forumMap", forumMap);
        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());

        return new UserIndicatorApplyViewModel("user/indicators-apply-publications", attrs);
    }

    private UserIndicatorApplyViewModel handleActivities(Indicator indicator, List<ActivityInstance> activities, Map<String, Object> attrs) {
        Map<String, Score> scores = activityReportingService.calculateActivityScores(activities, indicator);
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
        attrs.put("activities", activities);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());

        return new UserIndicatorApplyViewModel("user/indicators-apply-activities", attrs);
    }

    private UserIndicatorApplyViewModel handleCitations(Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Object> attrs) {
        AtomicInteger totalCit = new AtomicInteger();
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        Map<String, Map<String, Score>> scores = new HashMap<>();
        Map<String, Publication> citationsMap = new HashMap<>();

        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scopusCitationRepository.findAllByCitedIdIn(pubIds);
        List<String> allCitationsIds = allCitations.stream().map(Citation::getCitingId).collect(Collectors.toList());
        List<Publication> allByEidIn = scopusPublicationRepository.findAllByIdIn(allCitationsIds);
        Map<String, List<Publication>> citationsMapRetrieved = allByEidIn.stream().collect(Collectors.groupingBy(Publication::getId));

        Set<String> forumKeys = new HashSet<>();
        for (Publication pub : publications) {
            forumKeys.add(pub.getForum());
            List<Publication> citations = new ArrayList<>();
            for (Citation cit : allCitations) {
                if (cit.getCitedId().equals(pub.getId())) {
                    Publication citing = citationsMapRetrieved.get(cit.getCitingId()).get(0);
                    if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                        continue;
                    }
                    citations.add(citing);
                    citationsMap.put(citing.getTitle(), citing);
                    totalCit.getAndIncrement();
                }
            }

            citations.forEach(c -> forumKeys.add(c.getForum()));
            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(pub, citations, indicator);
            scores.put(pub.getTitle(), citScores);
        }

        applyFinalSelector(indicator, scores);
        double total = scores.values().stream().mapToDouble(s -> s.get("total").getAuthorScore()).sum();

        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));
        attrs.put("forumMap", forumMap);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> v.forEach((kk, vv) -> {
            quarterHistogram.putIfAbsent(vv.getQuarter(), 0);
            quarterHistogram.put(vv.getQuarter(), quarterHistogram.get(vv.getQuarter()) + 1);
        }));
        quarterHistogram.remove(null);

        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());
        attrs.put("total", String.format("%.2f", total));
        attrs.put("totalCit", totalCit.get());
        scores.remove("total");
        attrs.put("scores", scores);
        attrs.put("publications", publications);
        attrs.put("citationMap", citationsMap);

        return new UserIndicatorApplyViewModel("user/indicators-apply-citations", attrs);
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
                    Publication citing = pubCitationsMap.get(cit.getCitingId()).get(0);
                    if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                        continue;
                    }
                    citations.add(citing);
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
