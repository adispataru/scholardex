package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/**
 * WoS category detail page: headline metrics + one {@link WosCategoryMetricBlock} per metric (AIS/IF), each
 * anchored to its own reference year so the table never mixes metric eras. {@code journalCount} and
 * {@code latestYear} describe the primary (first) block's cohort; {@code archival} is true when every metric's
 * reference year is behind the dataset-wide latest (a retired category name — data kept, rendered historical).
 */
public record WosCategoryDetailViewModel(
        String key,
        String categoryName,
        String edition,
        long journalCount,
        Integer latestYear,
        boolean archival,
        List<WosCategoryMetricBlock> blocks,
        WosCategoryMetrics metrics
) {
}
