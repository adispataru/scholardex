package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            return normalizePayload(objectMapper.readValue(payload, Map.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize indicator payload.", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        return (Map<String, Object>) normalizeNode(payload);
    }

    @SuppressWarnings("unchecked")
    private Object normalizeNode(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), normalizeNode(value)));
            if (looksLikeScoreMap(normalized)) {
                return normalizeScore(normalized);
            }
            return normalized;
        }
        if (node instanceof List<?> rawList) {
            List<Object> normalized = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                normalized.add(normalizeNode(item));
            }
            return normalized;
        }
        return node;
    }

    private boolean looksLikeScoreMap(Map<String, Object> value) {
        return value.containsKey("score")
                || value.containsKey("authorScore")
                || value.containsKey("category")
                || value.containsKey("quarter")
                || value.containsKey("details")
                || value.containsKey("extra");
    }

    @SuppressWarnings("unchecked")
    private Score normalizeScore(Map<String, Object> value) {
        value.putIfAbsent("scoringSource", null);
        value.putIfAbsent("scoringInfo", new LinkedHashMap<>());

        Score score = new Score();
        score.setScore(asDouble(value.get("score")));
        score.setYear(asInt(value.get("year")));
        score.setCategory(asString(value.get("category")));
        score.setQuarter(asString(value.get("quarter")));
        score.setScoringSource(asString(value.get("scoringSource")));
        score.setScoringInfo(asMap(value.get("scoringInfo")));
        score.setAuthorScore(asDouble(value.get("authorScore")));
        score.setErrors(asStringMap(value.get("errors")));
        score.setExtra(asMap(value.get("extra")));
        score.setDetails(asString(value.get("details")));
        return score;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> asStringMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, String> stringMap = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> stringMap.put(String.valueOf(key), entryValue == null ? null : String.valueOf(entryValue)));
            return stringMap;
        }
        return new LinkedHashMap<>();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
