package ro.uvt.pokedex.core.service.application;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectionBackedReportingLookupFacade extends AbstractReportingLookupFacade {

    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final MongoTemplate mongoTemplate;

    public ProjectionBackedReportingLookupFacade(
            CacheService cacheService,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            MongoTemplate mongoTemplate,
            ReportingLookupMemoization reportingLookupMemoization) {
        super(cacheService, reportingLookupMemoization);
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    protected String memoBackendKey() {
        return "mongo";
    }

    @Override
    protected List<WoSRanking> loadRankingsByIssn(String normalizedIssn) {

        Query viewQuery = new Query().addCriteria(new Criteria().orOperator(
                Criteria.where("issnNorm").is(normalizedIssn),
                Criteria.where("eIssnNorm").is(normalizedIssn),
                Criteria.where("alternativeIssnsNorm").is(normalizedIssn)
        ));
        List<WosRankingView> views = mongoTemplate.find(viewQuery, WosRankingView.class);
        if (views.isEmpty()) {
            return List.of();
        }

        List<String> journalIds = views.stream().map(WosRankingView::getId).toList();
        List<WosMetricFact> metricFacts = metricFactRepository.findAllByJournalIdIn(journalIds);
        List<WosCategoryFact> categoryFacts = categoryFactRepository.findAllByJournalIdInAndEditionNormalizedIn(journalIds, OPERATIONAL_EDITIONS);

        Map<String, List<WosMetricFact>> scoresByJournal = new HashMap<>();
        for (WosMetricFact metricFact : metricFacts) {
            scoresByJournal.computeIfAbsent(metricFact.getJournalId(), ignored -> new ArrayList<>()).add(metricFact);
        }
        Map<String, List<WosCategoryFact>> categoriesByJournal = new HashMap<>();
        for (WosCategoryFact categoryFact : categoryFacts) {
            categoriesByJournal.computeIfAbsent(categoryFact.getJournalId(), ignored -> new ArrayList<>()).add(categoryFact);
        }

        List<WoSRanking> rankings = new ArrayList<>();
        for (WosRankingView view : views) {
            List<WosMetricFact> journalScores = scoresByJournal.getOrDefault(view.getId(), List.of());
            List<WosCategoryFact> journalCategories = categoriesByJournal.getOrDefault(view.getId(), List.of());
            if (journalScores.isEmpty() && journalCategories.isEmpty()) {
                continue;
            }
            rankings.add(toLegacyRanking(view, journalScores, journalCategories));
        }
        return rankings;
    }

    @Override
    protected Integer loadTopRankings(ParsedCategory parsedCategory, Integer year) {
        Set<EditionNormalized> editions = parsedCategory.editionNormalized() == null
                ? OPERATIONAL_EDITIONS
                : Set.of(parsedCategory.editionNormalized());

        Query scoringQuery = new Query().addCriteria(new Criteria().andOperator(
                Criteria.where("metricType").is(MetricType.AIS),
                Criteria.where("year").is(year),
                Criteria.where("quarter").is(WoSRanking.Quarter.Q1.toString()),
                Criteria.where("categoryNameCanonical").is(parsedCategory.categoryNameCanonical()),
                Criteria.where("editionNormalized").in(editions)
        ));

        List<String> journalIds = mongoTemplate.findDistinct(scoringQuery, "journalId", WosScoringView.class, String.class);
        return journalIds.size();
    }
}
