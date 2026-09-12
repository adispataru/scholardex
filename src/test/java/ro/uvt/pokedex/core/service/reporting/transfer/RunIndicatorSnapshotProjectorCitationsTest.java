package ro.uvt.pokedex.core.service.reporting.transfer;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H106 S2 — the run's stored citation graph carries a {@code scores} entry (just a "total") for EVERY
 * confirmed publication; only the ones with citing rows are tiles.
 */
class RunIndicatorSnapshotProjectorCitationsTest {

    private final RunIndicatorSnapshotProjector projector = new RunIndicatorSnapshotProjector(null);

    @Test
    void citedWorksWithoutCitingRowsAreNotTiles() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Uncited work", Map.of("total", score(0.0)));
        scores.put("Cited work", Map.of(
                "A citing paper", score(4.0),
                "total", score(4.0)));
        Map<String, Object> rawGraph = Map.of("outputMode", "citations", "scores", scores);
        IndicatorApplyResultDto result = new IndicatorApplyResultDto(
                "r1", "ind1", "user/indicators-apply", rawGraph, null, null, null, null, 1);

        List<CitationSnapshotItem> tiles = projector.projectCitations(result, "citations-per-publication");

        assertThat(tiles).extracting(CitationSnapshotItem::getPublicationTitle).containsExactly("Cited work");
        assertThat(tiles.get(0).getCitingPublications()).extracting(CitationSnapshotItem.CitingPublication::getTitle)
                .containsExactly("A citing paper");
        assertThat(tiles.get(0).getScore()).isEqualTo(4.0);
    }

    private static Map<String, Object> score(double points) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("score", points);
        s.put("authorScore", points);
        s.put("coreRankingEquivalent", points > 0 ? "B" : null);
        s.put("scoringInfo", Map.of());
        return s;
    }
}
