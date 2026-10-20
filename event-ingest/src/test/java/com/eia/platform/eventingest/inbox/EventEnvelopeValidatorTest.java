package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.api.FieldIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeValidatorTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID AGGREGATE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventEnvelopeValidator validator = new EventEnvelopeValidator(mapper);

    @Test
    void acceptsTheCanonicalEnvelope() {
        CanonicalEvent event = validator.validate(bytes(validEnvelope()));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo("direct.claim.registered");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.book()).isEqualTo("DIRECT");
        assertThat(event.aggregateType()).isEqualTo("Claim");
        assertThat(event.aggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.aggregateVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).hasToString("2026-10-19T13:04:11.240Z");
        assertThat(event.payloadHash()).hasSize(64);
    }

    @Test
    void rejectsEachMissingRequiredField() {
        for (String field : List.of(
                "eventId", "eventType", "eventVersion", "tenantId", "book",
                "aggregateType", "aggregateId", "aggregateVersion", "occurredAt", "data")) {
            ObjectNode envelope = validEnvelope();
            envelope.remove(field);

            assertThatThrownBy(() -> validator.validate(bytes(envelope)))
                    .as("missing %s", field)
                    .isInstanceOfSatisfying(EnvelopeValidationException.class, ex -> {
                        assertThat(ex.issues()).extracting(FieldIssue::field).contains(field);
                        assertThat(ex.issues()).extracting(FieldIssue::code).contains("REQUIRED");
                    });
        }
    }

    @Test
    void rejectsInvalidFieldValues() {
        assertInvalid("eventId", "not-a-uuid");
        assertInvalid("tenantId", "not-a-uuid");
        assertInvalid("aggregateId", "not-a-uuid");
        assertInvalid("eventType", "Direct.Claim.Registered");
        assertInvalid("eventType", "direct.claim");
        assertInvalid("eventVersion", 0);
        assertInvalid("aggregateVersion", 0);
        assertInvalid("book", "BOTH");
        assertInvalid("aggregateType", "");
        assertInvalid("occurredAt", "2026-10-19 13:04:11");

        ObjectNode envelope = validEnvelope();
        envelope.putArray("data").add("not-an-object");
        assertThatThrownBy(() -> validator.validate(bytes(envelope)))
                .isInstanceOfSatisfying(EnvelopeValidationException.class, ex ->
                        assertThat(ex.issues()).extracting(FieldIssue::field).contains("data"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> validator.validate("{not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(EnvelopeValidationException.class, ex ->
                        assertThat(ex.issues()).extracting(FieldIssue::code).contains("MALFORMED_JSON"));
    }

    @Test
    void rejectsAnEmptyBody() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOfSatisfying(EnvelopeValidationException.class, ex ->
                        assertThat(ex.issues()).extracting(FieldIssue::field).contains("$"));
    }

    @Test
    void normalizesBookAndAcceptsUnknownFieldsAndEventTypes() {
        ObjectNode envelope = validEnvelope();
        envelope.put("book", "direct");
        envelope.put("eventType", "mystery.widget.discovered");
        envelope.put("futureField", "ignored");

        CanonicalEvent event = validator.validate(bytes(envelope));

        assertThat(event.book()).isEqualTo("DIRECT");
        assertThat(event.eventType()).isEqualTo("mystery.widget.discovered");
    }

    @Test
    void canonicalHashIgnoresMemberOrder() {
        String first = """
                {"eventId":"0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90","eventType":"direct.claim.registered",
                 "eventVersion":1,"tenantId":"0192f3a4-0000-7000-8000-000000000001","book":"DIRECT",
                 "aggregateType":"Claim","aggregateId":"0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21",
                 "aggregateVersion":1,"occurredAt":"2026-10-19T13:04:11.240Z",
                 "data":{"claimNumber":"CLM-1","status":"OPEN"}}
                """;
        String second = """
                {"data":{"status":"OPEN","claimNumber":"CLM-1"},
                 "occurredAt":"2026-10-19T13:04:11.240Z","aggregateVersion":1,
                 "aggregateId":"0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21","aggregateType":"Claim",
                 "book":"DIRECT","tenantId":"0192f3a4-0000-7000-8000-000000000001",
                 "eventVersion":1,"eventType":"direct.claim.registered",
                 "eventId":"0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90"}
                """;

        CanonicalEvent a = validator.validate(first.getBytes(StandardCharsets.UTF_8));
        CanonicalEvent b = validator.validate(second.getBytes(StandardCharsets.UTF_8));

        assertThat(a.payloadHash()).isEqualTo(b.payloadHash());
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson());
    }

    private void assertInvalid(String field, Object value) {
        ObjectNode envelope = validEnvelope();
        if (value instanceof Integer integer) {
            envelope.put(field, integer);
        } else {
            envelope.put(field, String.valueOf(value));
        }

        assertThatThrownBy(() -> validator.validate(bytes(envelope)))
                .as("invalid %s=%s", field, value)
                .isInstanceOfSatisfying(EnvelopeValidationException.class, ex ->
                        assertThat(ex.issues()).extracting(FieldIssue::field).contains(field));
    }

    private ObjectNode validEnvelope() {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", EVENT_ID.toString());
        envelope.put("eventType", "direct.claim.registered");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", "2026-10-19T13:04:11.240Z");
        envelope.put("recordedAt", "2026-10-19T13:04:11.512Z");
        envelope.put("tenantId", TENANT_ID.toString());
        envelope.put("book", "DIRECT");
        envelope.put("vertical", "direct");
        envelope.put("aggregateType", "Claim");
        envelope.put("aggregateId", AGGREGATE_ID.toString());
        envelope.put("aggregateVersion", 1);
        envelope.put("sequence", 10422);
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.set("data", mapper.createObjectNode()
                .put("claimNumber", "CLM-2026-0193382")
                .put("status", "OPEN"));
        return envelope;
    }

    private static byte[] bytes(ObjectNode envelope) {
        try {
            return new ObjectMapper().writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
