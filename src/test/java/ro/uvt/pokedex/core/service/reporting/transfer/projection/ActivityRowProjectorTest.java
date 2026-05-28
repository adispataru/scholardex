package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityRowProjectorTest {

    @Test
    void projectsScoresFromDbDeserializedMapShapeAndTagsActivityName() {
        UserIndicatorResultService svc = mock(UserIndicatorResultService.class);
        Indicator indicator = activityIndicator("ind-1", "Premii");

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "activities");
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("act-best-paper", scoreMap("Best paper award (top conf)", "A", 8.0));
        scores.put("act-teaching",    scoreMap("Faculty teaching award",     "C", 2.0));
        scores.put("act-zero",        scoreMap("Below threshold",             null, 0.0));
        graph.put("scores", scores);

        when(svc.getOrCreateLatest("u@x", "ind-1"))
                .thenReturn(dto(graph));

        ActivityRowProjector projector = new ActivityRowProjector(svc);
        List<ActivitySnapshotItem> rows = projector.project("u@x", indicator, ActivityRowProjector.ROLE_KEY);

        assertThat(rows).hasSize(2);
        ActivitySnapshotItem best = rows.stream()
                .filter(r -> "Best paper award (top conf)".equals(r.getDescription())).findFirst().orElseThrow();
        assertThat(best.getRoleKey()).isEqualTo("activities-perspectiva-d");
        assertThat(best.getActivityName()).isEqualTo("Premii");
        assertThat(best.getCategory()).isEqualTo("A");
        assertThat(best.getScore()).isEqualTo(8.0);
        assertThat(rows).extracting(ActivitySnapshotItem::getDescription)
                .doesNotContain("Below threshold");
    }

    @Test
    void projectsScoresFromLiveScoreObjectShape() {
        UserIndicatorResultService svc = mock(UserIndicatorResultService.class);
        Indicator indicator = activityIndicator("ind-2", "Granturi");

        Score s = new Score();
        s.setAuthorScore(12.0);
        s.setCoreRankingEquivalent("AA");
        s.setDetails("PI on Horizon Europe project");

        Map<String, Object> graph = Map.of(
                "outputMode", "activities",
                "scores", Map.of("act-key", s)
        );
        when(svc.getOrCreateLatest("u@x", "ind-2")).thenReturn(dto(graph));

        ActivityRowProjector projector = new ActivityRowProjector(svc);
        List<ActivitySnapshotItem> rows = projector.project("u@x", indicator, ActivityRowProjector.ROLE_KEY);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActivityName()).isEqualTo("Granturi");
        assertThat(rows.get(0).getDescription()).isEqualTo("PI on Horizon Europe project");
        assertThat(rows.get(0).getCategory()).isEqualTo("AA");
        assertThat(rows.get(0).getScore()).isEqualTo(12.0);
    }

    @Test
    void unknownRoleKeyReturnsEmpty() {
        ActivityRowProjector projector = new ActivityRowProjector(mock(UserIndicatorResultService.class));
        assertThat(projector.project("u@x", activityIndicator("i", "Premii"), "not-the-role")).isEmpty();
    }

    @Test
    void indicatorWithoutActivityReturnsEmpty() {
        ActivityRowProjector projector = new ActivityRowProjector(mock(UserIndicatorResultService.class));
        Indicator bare = new Indicator();
        assertThat(projector.project("u@x", bare, ActivityRowProjector.ROLE_KEY)).isEmpty();
    }

    private Indicator activityIndicator(String id, String activityName) {
        Indicator i = new Indicator();
        i.setId(id);
        Activity a = new Activity();
        a.setId("a-" + id);
        a.setName(activityName);
        i.setActivity(a);
        return i;
    }

    private Map<String, Object> scoreMap(String details, String category, double authorScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("details", details);
        m.put("coreRankingEquivalent", category);
        m.put("authorScore", authorScore);
        return m;
    }

    private IndicatorApplyResultDto dto(Map<String, Object> graph) {
        return new IndicatorApplyResultDto(
                null,
                "ind",
                "indicator-view",
                graph,
                new IndicatorApplyResultDto.Summary(0.0, 0, List.of(), List.of()),
                IndicatorApplyResultDto.Source.PERSISTED,
                null, null, 0
        );
    }
}
