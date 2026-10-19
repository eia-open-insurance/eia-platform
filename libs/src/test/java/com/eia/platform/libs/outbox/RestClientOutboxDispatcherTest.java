package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientOutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-19T13:05:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");

    private OutboxProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestClientOutboxDispatcher dispatcher;
    private EventEnvelope envelope;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setEndpoint("https://subscriber.example/v1/events");
        properties.setSigningSecret("top-secret");
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = new RestClientOutboxDispatcher(
                builder.build(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
        envelope = envelope();
    }

    @Test
    void postsTheCanonicalBodyHeadersAndSignature() {
        String body = EventJson.toJson(envelope);
        String signature = RestClientOutboxDispatcher.signatureHeader(
                "top-secret", NOW.getEpochSecond(), body);
        server.expect(requestTo("https://subscriber.example/v1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_EVENT_ID, EVENT_ID.toString()))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_EVENT_TYPE, "party.party.created"))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_EVENT_VERSION, "1"))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_AGGREGATE_VERSION, "1"))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_IDEMPOTENCY_KEY, EVENT_ID.toString()))
                .andExpect(header(RestClientOutboxDispatcher.HEADER_SIGNATURE, signature))
                .andExpect(content().string(body))
                .andRespond(withSuccess());

        OutboxDispatchResult result = dispatcher.dispatch(envelope);

        assertThat(result.isDelivered()).isTrue();
        server.verify();
    }

    @Test
    void serverErrorIsTransient() {
        server.expect(requestTo("https://subscriber.example/v1/events"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        OutboxDispatchResult result = dispatcher.dispatch(envelope);

        assertThat(result.outcome()).isEqualTo(OutboxDispatchResult.Outcome.RETRY);
        assertThat(result.error()).contains("500");
    }

    @Test
    void conflictAndUnprocessableEntityArePermanent() {
        server.expect(requestTo("https://subscriber.example/v1/events"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThat(dispatcher.dispatch(envelope).outcome())
                .isEqualTo(OutboxDispatchResult.Outcome.DEAD);

        server.reset();
        server.expect(requestTo("https://subscriber.example/v1/events"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(dispatcher.dispatch(envelope).outcome())
                .isEqualTo(OutboxDispatchResult.Outcome.DEAD);
    }

    @Test
    void hmacMatchesTheStandardTestVector() {
        assertThat(RestClientOutboxDispatcher.hmacSha256(
                "key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        assertThat(RestClientOutboxDispatcher.signatureHeader("key", 123, "payload"))
                .startsWith("t=123,v1=");
    }

    private static EventEnvelope envelope() {
        return EventEnvelope.builder()
                .eventId(EVENT_ID)
                .eventType("party.party.created")
                .eventVersion(1)
                .occurredAt(Instant.parse("2026-10-19T13:04:11.240Z"))
                .recordedAt(Instant.parse("2026-10-19T13:04:11.512Z"))
                .tenantId(TENANT_ID)
                .book(Book.DIRECT)
                .vertical("party")
                .aggregateType("Party")
                .aggregateId(PARTY_ID)
                .aggregateVersion(1)
                .sequence(42)
                .correlationId(UUID.fromString("0192f3a4-3333-7000-8000-000000000003"))
                .actor(EventActor.system("eia-party-expert"))
                .subject(EventSubject.of("party", "party", PARTY_ID, "PTY-000004281"))
                .data(Map.of("partyNumber", "PTY-000004281"))
                .metadata(EventMetadata.v1("eia-party-expert@0.1.0"))
                .build();
    }
}
