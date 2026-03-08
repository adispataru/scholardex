package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.util.List;
import java.util.Map;

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
}
