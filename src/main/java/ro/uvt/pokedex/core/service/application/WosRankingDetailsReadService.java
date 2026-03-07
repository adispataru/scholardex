package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WosRankingDetailsReadService {

    private final WosRankingViewRepository rankingViewRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;

    public Optional<WoSRanking> findByJournalId(String journalId) {
        Optional<WosRankingView> viewOpt = rankingViewRepository.findById(journalId);
        if (viewOpt.isEmpty()) {
            return Optional.empty();
        }
        List<WosMetricFact> scoreFacts = metricFactRepository.findAllByJournalId(journalId);
        List<WosCategoryFact> categoryFacts = categoryFactRepository.findAllByJournalId(journalId);
        if (scoreFacts.isEmpty() && categoryFacts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toLegacyDetails(viewOpt.get(), scoreFacts, categoryFacts));
    }

    private WoSRanking toLegacyDetails(
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
                case AIS -> mergeScoreValue(score.getAis(), metricFact.getYear(), metricFact.getValue());
                case RIS -> mergeScoreValue(score.getRis(), metricFact.getYear(), metricFact.getValue());
                case IF -> mergeScoreValue(score.getIF(), metricFact.getYear(), metricFact.getValue());
            }
        }
        ranking.setScore(score);

        Map<String, WoSRanking.Rank> categories = new HashMap<>();
        for (WosCategoryFact categoryFact : categoryFacts) {
            if (categoryFact.getYear() == null || categoryFact.getMetricType() == null) {
                continue;
            }
            if (categoryFact.getCategoryNameCanonical() == null || categoryFact.getCategoryNameCanonical().isBlank()) {
                continue;
            }
            String categoryKey = categoryFact.getCategoryNameCanonical();
            if (categoryFact.getEditionNormalized() != null) {
                categoryKey = categoryKey + " - " + categoryFact.getEditionNormalized().name();
            }
            WoSRanking.Rank rank = categories.computeIfAbsent(categoryKey, ignored -> new WoSRanking.Rank());
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
        ranking.setWebOfScienceCategoryIndex(categories);
        return ranking;
    }

    private void mergeScoreValue(Map<Integer, Double> target, Integer year, Double value) {
        target.merge(year, value, Double::max);
    }

    private WoSRanking.Quarter parseQuarter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WoSRanking.Quarter.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }
}
