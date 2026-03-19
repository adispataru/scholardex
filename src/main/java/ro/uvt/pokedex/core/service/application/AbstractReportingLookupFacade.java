package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class AbstractReportingLookupFacade implements ReportingLookupPort {

    protected static final Set<EditionNormalized> OPERATIONAL_EDITIONS = EnumSet.of(
            EditionNormalized.SCIE,
            EditionNormalized.SSCI
    );

    protected final CacheService cacheService;
    protected final ReportingLookupMemoization reportingLookupMemoization;

    protected AbstractReportingLookupFacade(
            CacheService cacheService,
            ReportingLookupMemoization reportingLookupMemoization) {
        this.cacheService = cacheService;
        this.reportingLookupMemoization = reportingLookupMemoization;
    }

    /** Returns the memoization namespace key, e.g. "postgres" or "mongo". */
    protected abstract String memoBackendKey();

    /** Loads WoSRankings from the backing store for a pre-normalized ISSN. */
    protected abstract List<WoSRanking> loadRankingsByIssn(String normalizedIssn);

    /** Loads the top-ranking count from the backing store. */
    protected abstract Integer loadTopRankings(ParsedCategory parsedCategory, Integer year);

    @Override
    public Forum getForum(String forumId) {
        return cacheService.getCachedForums(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        String normalizedIssn = QueryNormalizationSupport.normalizeIssn(issn);
        if (normalizedIssn == null) {
            return List.of();
        }
        return reportingLookupMemoization.getOrCompute(
                memoBackendKey(),
                "rankingsByIssn",
                normalizedIssn,
                () -> loadRankingsByIssn(normalizedIssn)
        );
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
        String editionMemoKey = parsedCategory.editionNormalized() == null
                ? "OPERATIONAL"
                : parsedCategory.editionNormalized().name();
        String memoKey = year + "|" + parsedCategory.categoryNameCanonical() + "|" + editionMemoKey;
        return reportingLookupMemoization.getOrCompute(
                memoBackendKey(),
                "topRankings",
                memoKey,
                () -> loadTopRankings(parsedCategory, year)
        );
    }

    @Override
    public Set<String> getUniversityAuthorIds() {
        return cacheService.getUniversityAuthorIds();
    }

    protected WoSRanking toLegacyRanking(
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
        ranking.setAlternativeNames(view.getAlternativeNames() == null ? List.of() : view.getAlternativeNames());

        WoSRanking.Score score = new WoSRanking.Score();
        for (WosMetricFact metricFact : scoreFacts) {
            if (metricFact.getYear() == null || metricFact.getValue() == null || metricFact.getMetricType() == null) {
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

    protected WoSRanking.Quarter parseQuarter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WoSRanking.Quarter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }

    protected ParsedCategory parseCategoryIndex(String categoryIndex) {
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

    protected record ParsedCategory(String categoryNameCanonical, EditionNormalized editionNormalized) {
    }
}
