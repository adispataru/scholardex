package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        score.setCoreRankingEquivalent("A");
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

    @Test
    @SuppressWarnings("unchecked")
    void serializeSanitizesCanonicalPublicationViewsIntoJsonSafeMaps() {
        IndicatorPayloadSerializer serializer = new IndicatorPayloadSerializer(new ObjectMapper());
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("pub-1");
        publication.setTitle("Canonical publication");
        publication.setBuildAt(Instant.parse("2026-04-03T10:15:30Z"));

        Map<String, Object> payload = serializer.deserialize(
                serializer.serialize(Map.of("publications", List.of(publication)))
        );

        Object restored = ((List<Object>) payload.get("publications")).get(0);
        assertInstanceOf(Map.class, restored);
        assertEquals("pub-1", ((Map<String, Object>) restored).get("id"));
        assertEquals("2026-04-03T10:15:30Z", ((Map<String, Object>) restored).get("buildAt"));
        assertNotNull(((Map<String, Object>) restored).get("title"));
    }
}
