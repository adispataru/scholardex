package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
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

    public Optional<WoSRanking> findByJournalId(String journalId) {
        Optional<WosRankingView> viewOpt = rankingViewRepository.findById(journalId);
        if (viewOpt.isEmpty()) {
            return Optional.empty();
        }
        List<WosMetricFact> metricFacts = metricFactRepository.findAllByJournalId(journalId);
        if (metricFacts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toLegacyDetails(viewOpt.get(), metricFacts));
    }

    private WoSRanking toLegacyDetails(
            WosRankingView view,
            List<WosMetricFact> metricFacts
    ) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId(view.getId());
        ranking.setName(view.getName());
        ranking.setIssn(view.getIssn());
        ranking.setEIssn(view.getEIssn());
        ranking.setAlternativeIssns(view.getAlternativeIssns() == null ? List.of() : view.getAlternativeIssns());

        WoSRanking.Score score = new WoSRanking.Score();
        for (WosMetricFact metricFact : metricFacts) {
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
        for (WosMetricFact metricFact : metricFacts) {
            if (metricFact.getYear() == null || metricFact.getMetricType() == null) {
                continue;
            }
            if (metricFact.getCategoryNameCanonical() == null || metricFact.getCategoryNameCanonical().isBlank()) {
                continue;
            }
            String categoryKey = metricFact.getCategoryNameCanonical();
            if (metricFact.getEditionNormalized() != null) {
                categoryKey = categoryKey + " - " + metricFact.getEditionNormalized().name();
            }
            WoSRanking.Rank rank = categories.computeIfAbsent(categoryKey, ignored -> new WoSRanking.Rank());
            WoSRanking.Quarter quarter = parseQuarter(metricFact.getQuarter());
            switch (metricFact.getMetricType()) {
                case AIS -> {
                    if (quarter != null) {
                        rank.getQAis().put(metricFact.getYear(), quarter);
                    }
                    if (metricFact.getRank() != null) {
                        rank.getRankAis().put(metricFact.getYear(), metricFact.getRank());
                    }
                }
                case RIS -> {
                    if (quarter != null) {
                        rank.getQRis().put(metricFact.getYear(), quarter);
                    }
                    if (metricFact.getRank() != null) {
                        rank.getRankRis().put(metricFact.getYear(), metricFact.getRank());
                    }
                }
                case IF -> {
                    if (quarter != null) {
                        rank.getQIF().put(metricFact.getYear(), quarter);
                    }
                    if (metricFact.getRank() != null) {
                        rank.getRankIF().put(metricFact.getYear(), metricFact.getRank());
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
