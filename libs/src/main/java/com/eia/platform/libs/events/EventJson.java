package com.eia.platform.libs.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical JSON for the event envelope and outbox persistence.
 *
 * <p>The mapper is configured for deterministic output: map entries are ordered
 * by key, timestamps are ISO-8601 strings rather than epoch numbers, and null
 * envelope fields are omitted. Records retain their declared property order.
 * Two equal event values therefore always produce identical bytes, which is
 * required for HMAC signing and for stable idempotency hashes.</p>
 */
public final class EventJson {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private EventJson() {
    }

    public static String toJson(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize event JSON", ex);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Event JSON must not be blank");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to parse event JSON as " + type.getSimpleName(), ex);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to parse event payload JSON", ex);
        }
    }
}
