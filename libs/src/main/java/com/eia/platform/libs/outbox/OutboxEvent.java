package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.events.EventLink;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code outbox_event}, including the payload and header JSON that
 * reconstruct the canonical event envelope.
 *
 * <p>{@code sequence} is the database-assigned outbox sequence. It is 0 on a
 * row that has not been inserted yet; the database assigns the real value and
 * the dispatcher uses it for replay and gap detection.</p>
 */
public record OutboxEvent(
        UUID id,
        long sequence,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        int eventVersion,
        String payload,
        String headers,
        String partitionKey,
        Instant occurredAt,
        Instant recordedAt,
        Instant nextAttemptAt,
        Status status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt
) {
    public enum Status {
        PENDING,
        IN_FLIGHT,
        PUBLISHED,
        DEAD
    }

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 64) {
            throw new IllegalArgumentException("aggregateType must be 1..64 characters");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be at least 1");
        }
        if (eventType == null || eventType.isBlank() || eventType.length() > 96) {
            throw new IllegalArgumentException("eventType must be 1..96 characters");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(headers, "headers");
        if (partitionKey == null || partitionKey.isBlank() || partitionKey.length() > 128) {
            throw new IllegalArgumentException("partitionKey must be 1..128 characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static OutboxEvent fromEnvelope(EventEnvelope envelope) {
        return fromEnvelope(envelope, envelope.aggregateId().toString());
    }

    public static OutboxEvent fromEnvelope(EventEnvelope envelope, String partitionKey) {
        Objects.requireNonNull(envelope, "envelope");
        OutboxHeaders headers = new OutboxHeaders(
                envelope.book(),
                envelope.vertical(),
                envelope.correlationId(),
                envelope.causationId(),
                envelope.transactionId(),
                envelope.actor(),
                envelope.subject(),
                envelope.links(),
                envelope.metadata());
        return new OutboxEvent(
                envelope.eventId(),
                envelope.sequence(),
                envelope.tenantId(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.eventType(),
                envelope.eventVersion(),
                EventJson.toJson(envelope.data()),
                EventJson.toJson(headers),
                partitionKey,
                envelope.occurredAt(),
                envelope.recordedAt(),
                envelope.recordedAt(),
                Status.PENDING,
                0,
                null,
                envelope.recordedAt(),
                null);
    }

    /**
     * Reconstructs the canonical envelope for the dispatcher.
     */
    public EventEnvelope toEnvelope() {
        OutboxHeaders headerValues = EventJson.fromJson(headers, OutboxHeaders.class);
        Map<String, Object> data = EventJson.toMap(payload);
        String vertical = headerValues.vertical() != null
                ? headerValues.vertical()
                : eventType.substring(0, eventType.indexOf('.'));
        EventSubject subject = headerValues.subject() != null
                ? headerValues.subject()
                : new EventSubject(vertical, aggregateType, aggregateId, null);
        List<EventLink> links = headerValues.links() != null ? headerValues.links() : List.of();
        EventMetadata metadata = headerValues.metadata() != null
                ? headerValues.metadata()
                : EventMetadata.v1(null);
        Book book = headerValues.book() != null ? headerValues.book() : Book.DIRECT;
        return new EventEnvelope(
                id,
                eventType,
                eventVersion,
                occurredAt,
                recordedAt,
                tenantId,
                book,
                vertical,
                aggregateType,
                aggregateId,
                aggregateVersion,
                sequence,
                headerValues.correlationId(),
                headerValues.causationId(),
                headerValues.transactionId(),
                headerValues.actor(),
                subject,
                links,
                data,
                metadata);
    }

    public OutboxEvent withSequence(long sequence) {
        return new OutboxEvent(
                id, sequence, tenantId, aggregateType, aggregateId, aggregateVersion,
                eventType, eventVersion, payload, headers, partitionKey, occurredAt,
                recordedAt, nextAttemptAt, status, attempts, lastError, createdAt, sentAt);
    }

    public OutboxEvent withAttempts(int attempts) {
        return new OutboxEvent(
                id, sequence, tenantId, aggregateType, aggregateId, aggregateVersion,
                eventType, eventVersion, payload, headers, partitionKey, occurredAt,
                recordedAt, nextAttemptAt, status, attempts, lastError, createdAt, sentAt);
    }

    public OutboxEvent withStatus(Status status) {
        return new OutboxEvent(
                id, sequence, tenantId, aggregateType, aggregateId, aggregateVersion,
                eventType, eventVersion, payload, headers, partitionKey, occurredAt,
                recordedAt, nextAttemptAt, status, attempts, lastError, createdAt, sentAt);
    }

    /**
     * Marks the row claimed and sets the visibility lease expiry.
     */
    public OutboxEvent withClaim(Instant leaseUntil) {
        return new OutboxEvent(
                id, sequence, tenantId, aggregateType, aggregateId, aggregateVersion,
                eventType, eventVersion, payload, headers, partitionKey, occurredAt,
                recordedAt, leaseUntil, Status.IN_FLIGHT, attempts, lastError, createdAt, sentAt);
    }
}
