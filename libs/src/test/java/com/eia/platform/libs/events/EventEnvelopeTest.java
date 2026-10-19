package com.eia.platform.libs.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eia.platform.libs.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {
    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final UUID ACTOR_ID = UUID.fromString("0192f3a4-1111-7000-8000-000000000002");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T13:04:11.240Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-10-19T13:04:11.512Z");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesTheCanonicalEnvelopeFollowedByTheContract() throws Exception {
        EventEnvelope envelope = envelope(partyData());

        JsonNode node = json.readTree(EventJson.toJson(envelope));

        assertThat(node.get("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(node.get("eventType").asText()).isEqualTo("party.party.created");
        assertThat(node.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-10-19T13:04:11.240Z");
        assertThat(node.get("recordedAt").asText()).isEqualTo("2026-10-19T13:04:11.512Z");
        assertThat(node.get("tenantId").asText()).isEqualTo(TENANT_ID.toString());
        assertThat(node.get("book").asText()).isEqualTo("DIRECT");
        assertThat(node.get("vertical").asText()).isEqualTo("party");
        assertThat(node.get("aggregateType").asText()).isEqualTo("Party");
        assertThat(node.get("aggregateId").asText()).isEqualTo(PARTY_ID.toString());
        assertThat(node.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(node.get("sequence").asLong()).isEqualTo(10422L);
        assertThat(node.get("actor").get("type").asText()).isEqualTo("USER");
        assertThat(node.get("actor").get("displayName").asText()).isEqualTo("A. Adjuster");
        assertThat(node.get("subject").get("vertical").asText()).isEqualTo("party");
        assertThat(node.get("subject").get("type").asText()).isEqualTo("party");
        assertThat(node.get("subject").get("businessKey").asText()).isEqualTo("PTY-000004281");
        assertThat(node.get("links").get(0).get("linkType").asText()).isEqualTo("PARTY_HAS_ROLE");
        assertThat(node.get("links").get(0).get("target").get("vertical").asText()).isEqualTo("policy");
        assertThat(node.get("data").get("partyNumber").asText()).isEqualTo("PTY-000004281");
        assertThat(node.get("metadata").get("schemaVersion").asText()).isEqualTo("1.0.0");
        assertThat(node.get("metadata").get("sourceBuild").asText()).isEqualTo("party-expert@1.0.0+abc123");
        assertThat(node.has("causationId")).isFalse();
    }

    @Test
    void payloadSerializationIsDeterministicRegardlessOfMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("status", "PROSPECT");
        first.put("partyType", "PERSON");
        first.put("partyId", PARTY_ID);
        first.put("partyNumber", "PTY-000004281");
        first.put("roles", List.of());
        first.put("nested", nested("z", 1, "a", 2));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("nested", nested("a", 2, "z", 1));
        second.put("roles", List.of());
        second.put("partyNumber", "PTY-000004281");
        second.put("partyId", PARTY_ID);
        second.put("partyType", "PERSON");
        second.put("status", "PROSPECT");

        String firstJson = EventJson.toJson(envelope(first));
        String secondJson = EventJson.toJson(envelope(second));

        assertThat(firstJson).isEqualTo(secondJson);
        assertThat(EventJson.toJson(envelope(first))).isEqualTo(firstJson);
        assertThat(EventJson.toJson(first)).isEqualTo(
                "{\"nested\":{\"a\":2,\"z\":1},\"partyId\":\"" + PARTY_ID
                        + "\",\"partyNumber\":\"PTY-000004281\",\"partyType\":\"PERSON\","
                        + "\"roles\":[],\"status\":\"PROSPECT\"}");
    }

    @Test
    void deterministicEventIdsAreStableAndVersionFive() {
        UUID id = EventIds.deterministic(
                TENANT_ID, "Party", PARTY_ID, 1, "party.party.created");
        UUID again = EventIds.deterministic(
                TENANT_ID, "Party", PARTY_ID, 1, "party.party.created");
        UUID nextVersion = EventIds.deterministic(
                TENANT_ID, "Party", PARTY_ID, 2, "party.party.created");
        UUID otherType = EventIds.deterministic(
                TENANT_ID, "Party", PARTY_ID, 1, "party.party.updated");

        assertThat(id).isEqualTo(again);
        assertThat(id).isNotEqualTo(nextVersion);
        assertThat(id).isNotEqualTo(otherType);
        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void uuidV5MatchesTheRfc4122Vector() {
        UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertThat(EventIds.uuidV5(dnsNamespace, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void outboxRoundTripReconstructsTheEnvelopeWithTheDatabaseSequence() {
        EventEnvelope envelope = envelope(partyData());
        OutboxEvent row = OutboxEvent.fromEnvelope(envelope).withSequence(42);
        EventEnvelope reconstructed = row.toEnvelope();

        assertThat(reconstructed.eventId()).isEqualTo(EVENT_ID);
        assertThat(reconstructed.sequence()).isEqualTo(42);
        assertThat(reconstructed.eventType()).isEqualTo("party.party.created");
        assertThat(reconstructed.book()).isEqualTo(Book.DIRECT);
        assertThat(reconstructed.subject().businessKey()).isEqualTo("PTY-000004281");
        assertThat(reconstructed.actor().displayName()).isEqualTo("A. Adjuster");
        assertThat(reconstructed.links()).hasSize(1);
        assertThat(reconstructed.data().get("partyNumber")).isEqualTo("PTY-000004281");
        assertThat(reconstructed.metadata().schemaVersion()).isEqualTo("1.0.0");
        assertThat(row.partitionKey()).isEqualTo(PARTY_ID.toString());
    }

    @Test
    void rejectsEventNamesThatAreNotVerticalAggregateVerb() {
        assertThatThrownBy(() -> envelopeBuilder()
                .eventType("party.created")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> envelopeBuilder()
                .eventType("policy.party.created")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vertical");
    }

    private static EventEnvelope envelope(Map<String, Object> data) {
        return envelopeBuilder().data(data).build();
    }

    private static EventEnvelope.Builder envelopeBuilder() {
        return EventEnvelope.builder()
                .eventId(EVENT_ID)
                .eventType("party.party.created")
                .eventVersion(1)
                .occurredAt(OCCURRED_AT)
                .recordedAt(RECORDED_AT)
                .tenantId(TENANT_ID)
                .book(Book.DIRECT)
                .vertical("party")
                .aggregateType("Party")
                .aggregateId(PARTY_ID)
                .aggregateVersion(1)
                .sequence(10422)
                .correlationId(UUID.fromString("0192f3a4-3333-7000-8000-000000000003"))
                .actor(new EventActor("USER", ACTOR_ID, "A. Adjuster", null, "MFA"))
                .subject(EventSubject.of("party", "party", PARTY_ID, "PTY-000004281"))
                .links(List.of(new EventLink("PARTY_HAS_ROLE",
                        new EventTarget("policy", "policy",
                                UUID.fromString("0192f3a4-4444-7000-8000-000000000004")))))
                .metadata(EventMetadata.v1("party-expert@1.0.0+abc123"));
    }

    private static Map<String, Object> partyData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partyId", PARTY_ID);
        data.put("partyNumber", "PTY-000004281");
        data.put("partyType", "PERSON");
        data.put("status", "PROSPECT");
        data.put("roles", List.of());
        return data;
    }

    private static Map<String, Object> nested(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }
}
