package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
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
            return objectMapper.writeValueAsString(sanitizePayload(payload));
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
    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        return (Map<String, Object>) sanitizeNode(payload);
    }

    private Object sanitizeNode(Object node) {
        if (node == null
                || node instanceof String
                || node instanceof Number
                || node instanceof Boolean) {
            return node;
        }
        if (node instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (node instanceof Instant
                || node instanceof LocalDate
                || node instanceof LocalDateTime
                || node instanceof OffsetDateTime
                || node instanceof ZonedDateTime) {
            return String.valueOf(node);
        }
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> sanitized.put(String.valueOf(key), sanitizeNode(value)));
            return sanitized;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(sanitizeNode(item)));
            return sanitized;
        }
        if (node.getClass().isArray()) {
            int length = Array.getLength(node);
            List<Object> sanitized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeNode(Array.get(node, index)));
            }
            return sanitized;
        }
        return sanitizeBean(node);
    }

    private Object sanitizeBean(Object node) {
        try {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (PropertyDescriptor property : Introspector.getBeanInfo(node.getClass(), Object.class).getPropertyDescriptors()) {
                Method reader = property.getReadMethod();
                if (reader == null) {
                    continue;
                }
                sanitized.put(property.getName(), sanitizeNode(reader.invoke(node)));
            }
            return sanitized;
        } catch (IntrospectionException ex) {
            return String.valueOf(node);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to sanitize indicator payload bean.", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalizeNode(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            // A score's scoringInfo is a FREE-FORM map that legitimately carries score-shaped keys
            // (the journal scorer's provenance includes "quarter"); running the looksLikeScoreMap
            // heuristic on it converted it to a Score bean, and normalizeScore's asMap() then dropped
            // it to an EMPTY map — silently destroying zeroReason/feeJournal/provenance on every
            // cached read of such items. It must round-trip verbatim, never score-normalized.
            rawMap.forEach((key, value) -> {
                String name = String.valueOf(key);
                normalized.put(name, "scoringInfo".equals(name) ? normalizeVerbatim(value) : normalizeNode(value));
            });
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

    /** Structural copy with NO score detection — for subtrees that must stay plain data. */
    private Object normalizeVerbatim(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), normalizeVerbatim(value)));
            return normalized;
        }
        if (node instanceof List<?> rawList) {
            List<Object> normalized = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                normalized.add(normalizeVerbatim(item));
            }
            return normalized;
        }
        return node;
    }

    private boolean looksLikeScoreMap(Map<String, Object> value) {
        // H52 slice 11c: the {@code details}/{@code extra} markers used to disambiguate
        // historical score maps. Slice 9 added {@code multiplier} as the typed slot;
        // we still recognize the legacy markers for backward parsing of cached blobs
        // produced before slice 11c — no false positives in practice because the
        // remaining markers are all score-only.
        return value.containsKey("score")
                || value.containsKey("authorScore")
                || value.containsKey("coreRankingEquivalent") || value.containsKey("category")
                || value.containsKey("quarter")
                || value.containsKey("multiplier")
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
        score.setCoreRankingEquivalent(asString(value.containsKey("coreRankingEquivalent") ? value.get("coreRankingEquivalent") : value.get("category")));
        score.setQuarter(asString(value.get("quarter")));
        score.setScoringSource(asString(value.get("scoringSource")));
        score.setScoringInfo(asMap(value.get("scoringInfo")));
        score.setAuthorScore(asDouble(value.get("authorScore")));
        // H52 slice 11c: typed multiplier replaces the legacy {@code extra["M"]} read.
        // For cached blobs written before slice 9 (no top-level multiplier field),
        // fall back to the embedded extra map so the value survives the round-trip.
        Object typedMultiplier = value.get("multiplier");
        if (typedMultiplier instanceof Number n) {
            score.setMultiplier(n.intValue());
        } else if (value.get("extra") instanceof Map<?, ?> legacyExtra
                && legacyExtra.get("M") instanceof Number legacyM) {
            score.setMultiplier(legacyM.intValue());
        }
        // {@code errors}, {@code extra}, {@code details} keys present in pre-slice-11c
        // blobs are silently ignored — the Score model no longer has them. The cached
        // rawGraph Map still carries the original strings for templates that want them.
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
