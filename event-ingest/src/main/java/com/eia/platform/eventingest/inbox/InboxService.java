package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.api.FieldIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbox consumer entry point.
 *
 * <p>Order of checks: size, HMAC signature, envelope validation, transport
 * header consistency, then the idempotent insert. A duplicate id is compared by
 * canonical payload hash: identical means replay (200), different means
 * conflict (409).</p>
 */
@Service
public class InboxService {

    public static final int MAX_BODY_BYTES = 1_048_576;

    private final SignatureVerifier signatureVerifier;
    private final EventEnvelopeValidator validator;
    private final InboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public InboxService(
            SignatureVerifier signatureVerifier,
            EventEnvelopeValidator validator,
            InboxRepository repository,
            ObjectMapper mapper,
            Clock clock) {
        this.signatureVerifier = signatureVerifier;
        this.validator = validator;
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(byte[] body, HttpHeaders headers) {
        if (body != null && body.length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException(MAX_BODY_BYTES);
        }

        signatureVerifier.verify(headers.getFirst("X-EIA-Signature"), body);
        CanonicalEvent event = validator.validate(body);
        verifyTransportHeaders(event, headers);

        Instant receivedAt = clock.instant();
        boolean inserted = repository.insertIfAbsent(event, headersJson(headers), receivedAt);
        if (!inserted) {
            InboxEvent existing = repository.findById(event.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "inbox_event row disappeared for " + event.eventId()));
            if (!existing.payloadHash().equals(event.payloadHash())) {
                throw new EventConflictException(event.eventId());
            }
            return IngestResult.replayed(existing);
        }

        Instant processedAt = clock.instant();
        repository.markProcessed(event.eventId(), processedAt);
        return IngestResult.accepted(event, receivedAt, processedAt);
    }

    public List<InboxEvent> recent(int limit) {
        return repository.recent(limit);
    }

    /**
     * Newest-first activity for one aggregate. The tenant always comes from the
     * caller's transport context and is applied as a repository predicate, so a
     * caller can never read another tenant's events.
     */
    public List<AggregateEvent> aggregateActivity(UUID tenantId, UUID aggregateId, int limit) {
        return repository.findByAggregate(tenantId, aggregateId, limit);
    }

    public InboxStats stats(int eventTypeLimit) {
        return repository.stats(eventTypeLimit);
    }

    /**
     * The dispatcher duplicates identity in transport headers (SPEC 7.5.1).
     * Reject a mismatched header rather than trusting one of the two values.
     */
    private static void verifyTransportHeaders(CanonicalEvent event, HttpHeaders headers) {
        requireMatch(headers, "X-EIA-Event-Id", event.eventId().toString());
        requireMatch(headers, "X-EIA-Event-Type", event.eventType());
        requireMatch(headers, "X-EIA-Event-Version", Integer.toString(event.eventVersion()));
        requireMatch(headers, "X-EIA-Aggregate-Version", Long.toString(event.aggregateVersion()));
    }

    private static void requireMatch(HttpHeaders headers, String name, String expected) {
        String value = headers.getFirst(name);
        if (value != null && !value.isBlank() && !value.equals(expected)) {
            throw new EnvelopeValidationException(List.of(
                    new FieldIssue(name, "MISMATCH", name + " does not match the event envelope")));
        }
    }

    private String headersJson(HttpHeaders headers) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("contentType", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        stored.put("eventId", headers.getFirst("X-EIA-Event-Id"));
        stored.put("eventType", headers.getFirst("X-EIA-Event-Type"));
        stored.put("eventVersion", headers.getFirst("X-EIA-Event-Version"));
        stored.put("aggregateVersion", headers.getFirst("X-EIA-Aggregate-Version"));
        stored.put("idempotencyKey", headers.getFirst("Idempotency-Key"));
        stored.put("correlationId", headers.getFirst("X-Correlation-Id"));
        stored.put("traceparent", headers.getFirst("traceparent"));
        stored.put("userAgent", headers.getFirst(HttpHeaders.USER_AGENT));
        stored.put("signatureVerified", signatureVerifier.verificationRequired());
        stored.values().removeIf(value -> value == null);
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize inbox headers", ex);
        }
    }
}
