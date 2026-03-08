package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IndicatorPayloadSerializer {

    private final ObjectMapper objectMapper;

    public IndicatorPayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize indicator payload.", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize indicator payload.", ex);
        }
    }
}
