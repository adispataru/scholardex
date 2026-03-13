package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryJournalViewModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WosCategoryPageService {

    private static final Set<EditionNormalized> SUPPORTED_EDITIONS = Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI);

    private final WosCategoryFactRepository wosCategoryFactRepository;
    private final WosRankingViewRepository wosRankingViewRepository;

    public Optional<WosCategoryDetailViewModel> findCategory(String key) {
        Optional<CategoryKey> parsed = parseCategoryKey(key);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        CategoryKey categoryKey = parsed.get();
        List<WosCategoryFact> facts = wosCategoryFactRepository.findAllByCategoryNameCanonicalAndEditionNormalized(
                categoryKey.categoryName(),
                categoryKey.edition()
        );
        if (facts.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<WosCategoryFact>> factsByJournalId = facts.stream()
                .filter(fact -> fact.getJournalId() != null && !fact.getJournalId().isBlank())
                .collect(Collectors.groupingBy(WosCategoryFact::getJournalId, LinkedHashMap::new, Collectors.toList()));
        Map<String, WosRankingView> rankingViews = new LinkedHashMap<>();
        for (WosRankingView rankingView : wosRankingViewRepository.findAllById(factsByJournalId.keySet())) {
            rankingViews.put(rankingView.getId(), rankingView);
        }

        List<WosCategoryJournalViewModel> journals = new ArrayList<>();
        Integer latestYear = null;
        for (Map.Entry<String, List<WosCategoryFact>> entry : factsByJournalId.entrySet()) {
            List<WosCategoryFact> journalFacts = entry.getValue();
            WosRankingView view = rankingViews.get(entry.getKey());
            MetricSnapshot snapshot = buildMetricSnapshot(journalFacts);
            if (snapshot.latestYear != null && (latestYear == null || snapshot.latestYear > latestYear)) {
                latestYear = snapshot.latestYear;
            }
            journals.add(new WosCategoryJournalViewModel(
                    entry.getKey(),
                    view != null && view.getName() != null && !view.getName().isBlank() ? view.getName() : entry.getKey(),
                    view != null ? blankToDash(view.getIssn()) : "—",
                    view != null ? blankToDash(view.getEIssn()) : "—",
                    snapshot.latestYear,
                    snapshot.metricQuarter(MetricType.AIS),
                    snapshot.metricQuarter(MetricType.RIS),
                    snapshot.metricQuarter(MetricType.IF)
            ));
        }

        journals.sort(Comparator.comparing(WosCategoryJournalViewModel::journalName, String.CASE_INSENSITIVE_ORDER));
        return Optional.of(new WosCategoryDetailViewModel(
                key,
                categoryKey.categoryName(),
                categoryKey.edition().name(),
                journals.size(),
                latestYear,
                journals
        ));
    }

    public static String categoryKey(String categoryName, EditionNormalized edition) {
        return categoryName + " - " + edition.name();
    }

    private Optional<CategoryKey> parseCategoryKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        int separator = key.lastIndexOf(" - ");
        if (separator < 0) {
            return Optional.empty();
        }
        String categoryName = key.substring(0, separator).trim();
        String editionRaw = key.substring(separator + 3).trim().toUpperCase(Locale.ROOT);
        if (categoryName.isBlank()) {
            return Optional.empty();
        }
        try {
            EditionNormalized edition = EditionNormalized.valueOf(editionRaw);
            if (!SUPPORTED_EDITIONS.contains(edition)) {
                return Optional.empty();
            }
            return Optional.of(new CategoryKey(categoryName, edition));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private boolean hasSupportedCategory(WosCategoryFact fact) {
        return fact.getCategoryNameCanonical() != null
                && !fact.getCategoryNameCanonical().isBlank()
                && fact.getEditionNormalized() != null
                && SUPPORTED_EDITIONS.contains(fact.getEditionNormalized());
    }

    private MetricSnapshot buildMetricSnapshot(List<WosCategoryFact> facts) {
        MetricSnapshot snapshot = new MetricSnapshot();
        for (WosCategoryFact fact : facts) {
            if (fact.getYear() != null && (snapshot.latestYear == null || fact.getYear() > snapshot.latestYear)) {
                snapshot.latestYear = fact.getYear();
            }
            if (fact.getMetricType() == null || fact.getYear() == null) {
                continue;
            }
            MetricObservation current = snapshot.latestByMetric.get(fact.getMetricType());
            if (current == null || fact.getYear() > current.year) {
                snapshot.latestByMetric.put(fact.getMetricType(), new MetricObservation(fact.getYear(), normalizeQuarter(fact.getQuarter())));
            }
        }
        return snapshot;
    }

    private String normalizeQuarter(String rawQuarter) {
        if (rawQuarter == null || rawQuarter.isBlank()) {
            return "—";
        }
        String normalized = rawQuarter.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("Q") ? normalized : "Q" + normalized;
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record CategoryKey(String categoryName, EditionNormalized edition) {
    }
    private static final class MetricSnapshot {
        private Integer latestYear;
        private final Map<MetricType, MetricObservation> latestByMetric = new EnumMap<>(MetricType.class);

        private String metricQuarter(MetricType metricType) {
            MetricObservation observation = latestByMetric.get(metricType);
            return observation == null ? "—" : observation.quarter;
        }
    }

    private record MetricObservation(Integer year, String quarter) {
    }
}
