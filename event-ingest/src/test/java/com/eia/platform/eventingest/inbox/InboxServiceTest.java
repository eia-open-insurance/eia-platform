package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.EventIngestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventEnvelopeValidator validator = new EventEnvelopeValidator(mapper);
    private final InboxRepository repository = mock(InboxRepository.class);

    private byte[] body;
    private CanonicalEvent event;

    @BeforeEach
    void setUp() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "party.party.created");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", "2026-10-20T08:59:58.120Z");
        envelope.put("tenantId", UUID.randomUUID().toString());
        envelope.put("book", "DIRECT");
        envelope.put("aggregateType", "Party");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("aggregateVersion", 1);
        envelope.set("data", mapper.createObjectNode().put("partyNumber", "PTY-000000001"));
        body = mapper.writeValueAsBytes(envelope);
        event = validator.validate(body);
    }

    @Test
    void firstDeliveryStoresAndReportsAccepted() {
        when(repository.insertIfAbsent(any(), any(), eq(NOW))).thenReturn(true);
        InboxService service = service("");

        IngestResult result = service.ingest(body, headers(event));

        assertThat(result.replayed()).isFalse();
        assertThat(result.eventId()).isEqualTo(event.eventId());
        assertThat(result.receivedAt()).isEqualTo(NOW);
        assertThat(result.processedAt()).isEqualTo(NOW);
        verify(repository).markProcessed(event.eventId(), NOW);
    }

    @Test
    void replayWithIdenticalPayloadReturnsTheOriginalRow() {
        InboxEvent stored = existing(event.payloadHash());
        when(repository.insertIfAbsent(any(), any(), eq(NOW))).thenReturn(false);
        when(repository.findById(event.eventId())).thenReturn(Optional.of(stored));
        InboxService service = service("");

        IngestResult result = service.ingest(body, headers(event));

        assertThat(result.replayed()).isTrue();
        assertThat(result.receivedAt()).isEqualTo(stored.receivedAt());
        verify(repository, never()).markProcessed(any(), any());
    }

    @Test
    void replayWithDifferentPayloadIsAConflict() {
        when(repository.insertIfAbsent(any(), any(), eq(NOW))).thenReturn(false);
        when(repository.findById(event.eventId()))
                .thenReturn(Optional.of(existing("0".repeat(64))));
        InboxService service = service("");

        assertThatThrownBy(() -> service.ingest(body, headers(event)))
                .isInstanceOfSatisfying(EventConflictException.class,
                        ex -> assertThat(ex.eventId()).isEqualTo(event.eventId()));
        verify(repository, never()).markProcessed(any(), any());
    }

    @Test
    void rejectsABadSignatureBeforeTouchingTheRepository() {
        InboxService service = service("smoke-secret");
        HttpHeaders headers = headers(event);
        headers.set("X-EIA-Signature", "t=1,v1=" + "0".repeat(64));

        assertThatThrownBy(() -> service.ingest(body, headers))
                .isInstanceOf(InvalidSignatureException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsAValidSignatureIncludingTheBody() {
        String secret = "smoke-secret";
        InboxService service = service(secret);
        HttpHeaders headers = headers(event);
        long timestamp = NOW.getEpochSecond();
        String bodyText = new String(body, StandardCharsets.UTF_8);
        headers.set("X-EIA-Signature",
                "t=" + timestamp + ",v1=" + SignatureVerifier.hmacSha256(secret, timestamp + "." + bodyText));
        when(repository.insertIfAbsent(any(), any(), eq(NOW))).thenReturn(true);

        IngestResult result = service.ingest(body, headers);

        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<String> headersJson = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(eq(event), headersJson.capture(), eq(NOW));
        assertThat(headersJson.getValue()).contains("\"signatureVerified\":true");
    }

    @Test
    void rejectsTransportHeadersThatDisagreeWithTheEnvelope() {
        InboxService service = service("");
        HttpHeaders headers = headers(event);
        headers.set("X-EIA-Event-Type", "direct.claim.registered");

        assertThatThrownBy(() -> service.ingest(body, headers))
                .isInstanceOfSatisfying(EnvelopeValidationException.class, ex ->
                        assertThat(ex.issues()).extracting("field").contains("X-EIA-Event-Type"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBodiesOverOneMegabyte() {
        InboxService service = service("");

        assertThatThrownBy(() -> service.ingest(new byte[InboxService.MAX_BODY_BYTES + 1], new HttpHeaders()))
                .isInstanceOf(PayloadTooLargeException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void recentAndStatsDelegateToTheRepository() {
        InboxService service = service("");
        when(repository.recent(5)).thenReturn(java.util.List.of());
        when(repository.stats(anyInt())).thenReturn(new InboxStats(
                0, 0, 0, 0, null, null, java.util.List.of(), java.util.Map.of(), NOW));

        assertThat(service.recent(5)).isEmpty();
        assertThat(service.stats(5).totalEvents()).isZero();
    }

    @Test
    void aggregateActivityIsScopedToTheTenantAndLimit() {
        InboxService service = service("");
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        when(repository.findByAggregate(tenantId, aggregateId, 50)).thenReturn(java.util.List.of());

        assertThat(service.aggregateActivity(tenantId, aggregateId, 50)).isEmpty();

        verify(repository).findByAggregate(tenantId, aggregateId, 50);
    }

    private InboxService service(String secret) {
        EventIngestProperties properties = new EventIngestProperties();
        properties.setSigningSecret(secret);
        return new InboxService(
                new SignatureVerifier(properties), validator, repository, mapper, CLOCK);
    }

    private static HttpHeaders headers(CanonicalEvent event) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-EIA-Event-Id", event.eventId().toString());
        headers.set("X-EIA-Event-Type", event.eventType());
        headers.set("X-EIA-Event-Version", Integer.toString(event.eventVersion()));
        headers.set("X-EIA-Aggregate-Version", Long.toString(event.aggregateVersion()));
        headers.set("Idempotency-Key", event.eventId().toString());
        return headers;
    }

    private static InboxEvent existing(String payloadHash) {
        return new InboxEvent(
                UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90"),
                "party.party.created",
                UUID.fromString("0192f3a4-0000-7000-8000-000000000001"),
                "DIRECT",
                "Party",
                UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21"),
                1,
                1,
                NOW.minusSeconds(30),
                NOW.minusSeconds(20),
                NOW.minusSeconds(19),
                "PROCESSED",
                payloadHash);
    }
}
