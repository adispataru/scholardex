package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorPayloadSerializerTest {

    @Test
    void serializeSupportsOptionalValuesFromActivityInstance() {
        IndicatorPayloadSerializer serializer = new IndicatorPayloadSerializer(new ObjectMapper());

        ActivityInstance activity = new ActivityInstance();
        activity.setId("a-1");
        activity.setDate("2024-05-01");

        String json = serializer.serialize(Map.of("activities", List.of(activity)));

        assertTrue(json.contains("\"activities\""));
        assertTrue(json.contains("\"yearOptional\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deserializeRestoresScoreObjectsWithProvenance() {
        IndicatorPayloadSerializer serializer = new IndicatorPayloadSerializer(new ObjectMapper());
        Score score = new Score();
        score.setCategory("A");
        score.setQuarter("Q1");
        score.setScoringSource("SCOPUS+CORE");
        score.setScoringInfo(Map.of("matchSource", "SCOPUS"));

        Map<String, Object> payload = serializer.deserialize(
                serializer.serialize(Map.of("scores", Map.of("Paper", score)))
        );

        Object restored = ((Map<String, Object>) payload.get("scores")).get("Paper");
        assertInstanceOf(Score.class, restored);
        assertEquals("SCOPUS+CORE", ((Score) restored).getScoringSource());
        assertEquals("SCOPUS", ((Score) restored).getScoringInfo().get("matchSource"));
    }
}
