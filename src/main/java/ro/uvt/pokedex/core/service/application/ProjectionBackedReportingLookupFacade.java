package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Primary
@RequiredArgsConstructor
public class ProjectionBackedReportingLookupFacade implements ReportingLookupPort {

    private static final Set<EditionNormalized> OPERATIONAL_EDITIONS = EnumSet.of(
            EditionNormalized.SCIE,
            EditionNormalized.SSCI
    );

    private final CacheService cacheService;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public Forum getForum(String forumId) {
        return cacheService.getCachedForums(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        String normalizedIssn = normalizeIssn(issn);
        if (normalizedIssn == null) {
            return List.of();
        }

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
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return cacheService.getCachedConfRankings(acronym);
    }

    @Override
    public int getTopRankings(String categoryIndex, Integer year) {
        if (year == null || categoryIndex == null || categoryIndex.isBlank()) {
            return 0;
        }
        ParsedCategory parsedCategory = parseCategoryIndex(categoryIndex);
        if (parsedCategory.categoryNameCanonical().isBlank()) {
            return 0;
        }

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

    @Override
    public Set<String> getUniversityAuthorIds() {
        return cacheService.getUniversityAuthorIds();
    }

    private WoSRanking toLegacyRanking(
            WosRankingView view,
            List<WosMetricFact> scoreFacts,
            List<WosCategoryFact> categoryFacts
    ) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId(view.getId());
        ranking.setName(view.getName());
        ranking.setIssn(view.getIssn());
        ranking.setEIssn(view.getEIssn());
        ranking.setAlternativeIssns(view.getAlternativeIssns() == null ? List.of() : view.getAlternativeIssns());

        WoSRanking.Score score = new WoSRanking.Score();
        for (WosMetricFact metricFact : scoreFacts) {
            if (metricFact.getYear() == null || metricFact.getValue() == null) {
                continue;
            }
            switch (metricFact.getMetricType()) {
                case AIS -> score.getAis().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
                case RIS -> score.getRis().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
                case IF -> score.getIF().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
            }
        }
        ranking.setScore(score);

        Map<String, WoSRanking.Rank> categoryIndex = new LinkedHashMap<>();
        for (WosCategoryFact categoryFact : categoryFacts) {
            if (categoryFact.getCategoryNameCanonical() == null || categoryFact.getCategoryNameCanonical().isBlank()) {
                continue;
            }
            if (categoryFact.getYear() == null || categoryFact.getMetricType() == null) {
                continue;
            }
            String key = categoryFact.getCategoryNameCanonical() + " - " + categoryFact.getEditionNormalized();
            WoSRanking.Rank rank = categoryIndex.computeIfAbsent(key, ignored -> new WoSRanking.Rank());
            WoSRanking.Quarter quarter = parseQuarter(categoryFact.getQuarter());
            switch (categoryFact.getMetricType()) {
                case AIS -> {
                    if (quarter != null) {
                        rank.getQAis().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankAis().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankAis().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
                case RIS -> {
                    if (quarter != null) {
                        rank.getQRis().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankRis().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankRis().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
                case IF -> {
                    if (quarter != null) {
                        rank.getQIF().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankIF().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankIF().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
            }
        }
        ranking.setWebOfScienceCategoryIndex(categoryIndex);
        return ranking;
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    private WoSRanking.Quarter parseQuarter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WoSRanking.Quarter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }

    private ParsedCategory parseCategoryIndex(String categoryIndex) {
        String normalized = categoryIndex == null ? "" : categoryIndex.trim();
        if (normalized.isBlank()) {
            return new ParsedCategory("", null);
        }
        int delimiter = normalized.lastIndexOf('-');
        if (delimiter < 0 || delimiter == normalized.length() - 1) {
            return new ParsedCategory(normalized, null);
        }
        String categoryName = normalized.substring(0, delimiter).trim();
        String editionToken = normalized.substring(delimiter + 1).trim().toUpperCase(Locale.ROOT);
        EditionNormalized edition = null;
        if ("SCIE".equals(editionToken)) {
            edition = EditionNormalized.SCIE;
        } else if ("SSCI".equals(editionToken)) {
            edition = EditionNormalized.SSCI;
        }
        if (categoryName.isBlank()) {
            return new ParsedCategory("", edition);
        }
        return new ParsedCategory(categoryName, edition);
    }

    private record ParsedCategory(String categoryNameCanonical, EditionNormalized editionNormalized) {
    }
}
