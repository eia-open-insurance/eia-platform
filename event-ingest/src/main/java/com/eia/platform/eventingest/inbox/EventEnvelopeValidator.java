package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.api.FieldIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and canonicalizes the inbound event envelope (SPEC 7.5.4 and 12.4).
 *
 * <p>Required fields are exactly the contract fields the outbox dispatcher
 * sends: eventId, eventType, eventVersion, tenantId, book, aggregateType,
 * aggregateId, aggregateVersion, occurredAt, and data. Unknown fields and
 * unknown event types are accepted (SPEC EV-06).</p>
 */
@Component
public class EventEnvelopeValidator {

    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");
    private static final Set<String> BOOKS = Set.of("DIRECT", "ASSUMED");
    private static final int MAX_ISSUES = 20;

    private final ObjectMapper mapper;

    public EventEnvelopeValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CanonicalEvent validate(byte[] body) {
        if (body == null || body.length == 0) {
            throw new EnvelopeValidationException(List.of(
                    new FieldIssue("$", "REQUIRED", "request body must be a JSON event envelope")));
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (java.io.IOException ex) {
            throw new EnvelopeValidationException(List.of(
                    new FieldIssue("$", "MALFORMED_JSON", "request body is not valid JSON")));
        }
        if (root == null || !root.isObject()) {
            throw new EnvelopeValidationException(List.of(
                    new FieldIssue("$", "INVALID", "event envelope must be a JSON object")));
        }

        List<FieldIssue> issues = new ArrayList<>();
        UUID eventId = uuid(root, "eventId", issues);
        String eventType = eventType(root, issues);
        int eventVersion = eventVersion(root, issues);
        UUID tenantId = uuid(root, "tenantId", issues);
        String book = book(root, issues);
        String aggregateType = aggregateType(root, issues);
        UUID aggregateId = uuid(root, "aggregateId", issues);
        long aggregateVersion = aggregateVersion(root, issues);
        Instant occurredAt = occurredAt(root, issues);
        data(root, issues);

        if (!issues.isEmpty()) {
            throw new EnvelopeValidationException(issues.size() > MAX_ISSUES
                    ? issues.subList(0, MAX_ISSUES)
                    : issues);
        }

        String canonicalJson = canonicalJson(root);
        return new CanonicalEvent(
                eventId,
                eventType,
                eventVersion,
                occurredAt,
                tenantId,
                book,
                aggregateType,
                aggregateId,
                aggregateVersion,
                canonicalJson,
                sha256(canonicalJson));
    }

    private static UUID uuid(JsonNode root, String field, List<FieldIssue> issues) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue(field, "REQUIRED", field + " is required"));
            return null;
        }
        if (!node.isTextual()) {
            issues.add(new FieldIssue(field, "INVALID", field + " must be a UUID string"));
            return null;
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (IllegalArgumentException ex) {
            issues.add(new FieldIssue(field, "INVALID", field + " must be a valid UUID"));
            return null;
        }
    }

    private static String eventType(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("eventType");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("eventType", "REQUIRED", "eventType is required"));
            return null;
        }
        if (!node.isTextual()) {
            issues.add(new FieldIssue("eventType", "INVALID", "eventType must be a string"));
            return null;
        }
        String value = node.textValue();
        if (value.isBlank() || value.length() > 96 || !EVENT_TYPE.matcher(value).matches()) {
            issues.add(new FieldIssue("eventType", "INVALID",
                    "eventType must be {vertical}.{aggregate}.{past-tense-verb} in kebab case"));
            return null;
        }
        return value;
    }

    private static int eventVersion(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("eventVersion");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("eventVersion", "REQUIRED", "eventVersion is required"));
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            issues.add(new FieldIssue("eventVersion", "INVALID", "eventVersion must be an integer >= 1"));
            return 0;
        }
        return node.intValue();
    }

    private static String book(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("book");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("book", "REQUIRED", "book is required"));
            return null;
        }
        if (!node.isTextual()) {
            issues.add(new FieldIssue("book", "INVALID", "book must be DIRECT or ASSUMED"));
            return null;
        }
        String value = node.textValue().trim().toUpperCase(Locale.ROOT);
        if (!BOOKS.contains(value)) {
            issues.add(new FieldIssue("book", "INVALID", "book must be DIRECT or ASSUMED"));
            return null;
        }
        return value;
    }

    private static String aggregateType(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("aggregateType");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("aggregateType", "REQUIRED", "aggregateType is required"));
            return null;
        }
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 64) {
            issues.add(new FieldIssue("aggregateType", "INVALID",
                    "aggregateType must be 1..64 characters"));
            return null;
        }
        return node.textValue();
    }

    private static long aggregateVersion(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("aggregateVersion");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("aggregateVersion", "REQUIRED", "aggregateVersion is required"));
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) {
            issues.add(new FieldIssue("aggregateVersion", "INVALID",
                    "aggregateVersion must be an integer >= 1"));
            return 0;
        }
        return node.longValue();
    }

    private static Instant occurredAt(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("occurredAt");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("occurredAt", "REQUIRED", "occurredAt is required"));
            return null;
        }
        if (!node.isTextual()) {
            issues.add(new FieldIssue("occurredAt", "INVALID",
                    "occurredAt must be an RFC 3339 timestamp string"));
            return null;
        }
        try {
            return Instant.parse(node.textValue());
        } catch (DateTimeParseException ex) {
            issues.add(new FieldIssue("occurredAt", "INVALID",
                    "occurredAt must be an RFC 3339 timestamp with an offset"));
            return null;
        }
    }

    private static JsonNode data(JsonNode root, List<FieldIssue> issues) {
        JsonNode node = root.get("data");
        if (node == null || node.isNull()) {
            issues.add(new FieldIssue("data", "REQUIRED", "data is required"));
            return null;
        }
        if (!node.isObject()) {
            issues.add(new FieldIssue("data", "INVALID", "data must be a JSON object"));
            return null;
        }
        return node;
    }

    private String canonicalJson(JsonNode root) {
        try {
            return mapper.writeValueAsString(sortKeys(root));
        } catch (JsonProcessingException ex) {
            throw new EnvelopeValidationException(List.of(
                    new FieldIssue("$", "INVALID", "event envelope cannot be canonicalized")));
        }
    }

    /**
     * Deterministic JSON: object members are sorted by name at every depth.
     * Two semantically identical envelopes therefore hash identically even if
     * the producer changed member order.
     */
    private JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            for (JsonNode child : node) {
                array.add(sortKeys(child));
            }
            return array;
        }
        return node;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM", ex);
        }
    }
}
