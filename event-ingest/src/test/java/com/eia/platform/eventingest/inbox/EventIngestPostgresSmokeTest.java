package com.eia.platform.eventingest.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live PostgreSQL smoke test.
 *
 * <p>Requires {@code EIA_PG_SMOKE_URL}, for example
 * {@code jdbc:postgresql://localhost:55432/eia_platform}. The database must
 * exist; the service creates its idempotent schema on startup. The test starts
 * the real HTTP server with HMAC verification enabled and exercises the whole
 * contract: 202, 200 replay, 409 conflict, 401 bad signature, 400 validation,
 * and the dashboard read endpoints.</p>
 */
@EnabledIfEnvironmentVariable(named = "EIA_PG_SMOKE_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eia.event-ingest.signing-secret=smoke-secret",
                "spring.sql.init.mode=always"
        })
class EventIngestPostgresSmokeTest {

    private static final String SECRET = "smoke-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("EIA_PG_SMOKE_URL"));
        registry.add("spring.datasource.username",
                () -> envOrDefault("EIA_PG_SMOKE_USER", "eia"));
        registry.add("spring.datasource.password",
                () -> envOrDefault("EIA_PG_SMOKE_PASSWORD", "replace-me-local-only"));
    }

    @Test
    void ingestsIdempotentlyAndServesDashboardQueries() throws Exception {
        UUID eventId = UUID.randomUUID();
        String eventType = "party.party.smoke-tested";
        ObjectNode envelope = envelope(eventId, eventType);
        String body = mapper.writeValueAsString(envelope);
        List<UUID> created = new ArrayList<>();

        try {
            HttpResponse<String> first = post(body, envelope, sign(body));
            assertThat(first.statusCode()).isEqualTo(202);
            JsonNode firstJson = mapper.readTree(first.body());
            assertThat(firstJson.path("replayed").asBoolean()).isFalse();
            assertThat(firstJson.path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(firstJson.path("eventId").asText()).isEqualTo(eventId.toString());
            created.add(eventId);

            HttpResponse<String> replay = post(body, envelope, sign(body));
            assertThat(replay.statusCode()).isEqualTo(200);
            JsonNode replayJson = mapper.readTree(replay.body());
            assertThat(replayJson.path("replayed").asBoolean()).isTrue();
            assertThat(replayJson.path("status").asText()).isEqualTo("REPLAYED");

            ObjectNode conflicting = envelope(eventId, eventType);
            ((ObjectNode) conflicting.get("data")).put("status", "CLOSED");
            HttpResponse<String> conflict = post(
                    mapper.writeValueAsString(conflicting), conflicting,
                    sign(mapper.writeValueAsString(conflicting)));
            assertThat(conflict.statusCode()).isEqualTo(409);
            JsonNode conflictJson = mapper.readTree(conflict.body());
            assertThat(conflictJson.path("code").asText()).isEqualTo("EIA-PLT-4090");
            assertThat(conflictJson.path("meta").path("eventId").asText()).isEqualTo(eventId.toString());

            ObjectNode unsigned = envelope(UUID.randomUUID(), eventType);
            HttpResponse<String> badSignature = post(
                    mapper.writeValueAsString(unsigned), unsigned,
                    "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64));
            assertThat(badSignature.statusCode()).isEqualTo(401);
            assertThat(mapper.readTree(badSignature.body()).path("code").asText())
                    .isEqualTo("EIA-PLT-4010");

            ObjectNode invalid = envelope(UUID.randomUUID(), eventType);
            invalid.remove("tenantId");
            HttpResponse<String> invalidResponse = post(
                    mapper.writeValueAsString(invalid), invalid,
                    sign(mapper.writeValueAsString(invalid)));
            assertThat(invalidResponse.statusCode()).isEqualTo(400);
            JsonNode invalidJson = mapper.readTree(invalidResponse.body());
            assertThat(invalidJson.path("code").asText()).isEqualTo("EIA-PLT-4001");
            assertThat(invalidJson.path("errors").toString()).contains("tenantId");

            HttpResponse<String> recent = get("/api/v1/events/recent?limit=5");
            assertThat(recent.statusCode()).isEqualTo(200);
            JsonNode recentJson = mapper.readTree(recent.body());
            assertThat(recentJson.path("items").toString()).contains(eventId.toString());
            assertThat(recentJson.path("items").get(0).has("payload")).isFalse();

            HttpResponse<String> stats = get("/api/v1/events/stats");
            assertThat(stats.statusCode()).isEqualTo(200);
            JsonNode statsJson = mapper.readTree(stats.body());
            assertThat(statsJson.path("totalEvents").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(statsJson.path("processedEvents").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(statsJson.path("recentEventTypes").toString()).contains(eventType);
            assertThat(statsJson.path("byBook").path("DIRECT").asLong()).isGreaterThanOrEqualTo(1);

            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT event_type, business_book, status, processed_at,
                           payload->>'eventId' AS payload_event_id,
                           headers->>'signatureVerified' AS signature_verified
                      FROM inbox_event
                     WHERE event_id = ?
                    """, eventId);
            assertThat(row.get("event_type")).isEqualTo(eventType);
            assertThat(row.get("business_book")).isEqualTo("DIRECT");
            assertThat(row.get("status")).isEqualTo("PROCESSED");
            assertThat(row.get("processed_at")).isNotNull();
            assertThat(row.get("payload_event_id")).isEqualTo(eventId.toString());
            assertThat(row.get("signature_verified")).isEqualTo("true");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM inbox_event WHERE event_id = ?", Long.class, eventId))
                    .isEqualTo(1L);
        } finally {
            for (UUID id : created) {
                jdbc.update("DELETE FROM inbox_event WHERE event_id = ?", id);
            }
        }
    }

    private HttpResponse<String> post(String body, ObjectNode envelope, String signature)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + "/internal/events"))
                .header("Content-Type", "application/json")
                .header("X-EIA-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        addIfPresent(request, "X-EIA-Event-Id", envelope, "eventId");
        addIfPresent(request, "X-EIA-Event-Type", envelope, "eventType");
        addIfPresent(request, "X-EIA-Event-Version", envelope, "eventVersion");
        addIfPresent(request, "X-EIA-Aggregate-Version", envelope, "aggregateVersion");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String sign(String body) {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + SignatureVerifier.hmacSha256(SECRET, timestamp + "." + body);
    }

    private static void addIfPresent(
            HttpRequest.Builder request, String header, ObjectNode envelope, String field) {
        JsonNode value = envelope.get(field);
        if (value != null && !value.isNull()) {
            request.header(header, value.asText());
        }
    }

    private ObjectNode envelope(UUID eventId, String eventType) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("recordedAt", Instant.now().toString());
        envelope.put("tenantId", "11111111-1111-7111-8111-111111111111");
        envelope.put("book", "DIRECT");
        envelope.put("vertical", "party");
        envelope.put("aggregateType", "Party");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("aggregateVersion", 1);
        envelope.set("data", mapper.createObjectNode()
                .put("partyNumber", "PTY-SMOKE-" + eventId.toString().substring(0, 8))
                .put("status", "ACTIVE"));
        return envelope;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
