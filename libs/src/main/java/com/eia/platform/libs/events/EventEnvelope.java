package com.eia.platform.libs.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Canonical EIA domain event envelope (SPEC 7.5.4 and 12.4,
 * {@code contracts/schemas/event-envelope.schema.json}).
 *
 * <p>Only the outbox dispatcher fills {@code sequence}; a new event uses
 * {@code 0} until the row is inserted and the database assigns the sequence.
 * {@code recordedAt} is the instant the outbox row is written.</p>
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Instant recordedAt,
        UUID tenantId,
        Book book,
        String vertical,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        long sequence,
        UUID correlationId,
        UUID causationId,
        UUID transactionId,
        EventActor actor,
        EventSubject subject,
        List<EventLink> links,
        Map<String, Object> data,
        EventMetadata metadata
) {
    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        if (eventType == null || !EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType must be {vertical}.{aggregate}.{past-tense-verb} in kebab case: " + eventType);
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(book, "book");
        EventVerticals.require(vertical);
        if (!eventType.startsWith(vertical + ".")) {
            throw new IllegalArgumentException(
                    "eventType " + eventType + " does not start with vertical " + vertical);
        }
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 64) {
            throw new IllegalArgumentException("aggregateType must be 1..64 characters");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be at least 1");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(subject, "subject");
        links = links == null ? List.of() : List.copyOf(links);
        data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        metadata = metadata == null ? EventMetadata.v1(null) : metadata;
    }

    /**
     * Returns a copy with the database-assigned outbox sequence.
     */
    public EventEnvelope withSequence(long sequence) {
        return new EventEnvelope(
                eventId, eventType, eventVersion, occurredAt, recordedAt, tenantId, book,
                vertical, aggregateType, aggregateId, aggregateVersion, sequence,
                correlationId, causationId, transactionId, actor, subject, links, data, metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID eventId;
        private String eventType;
        private int eventVersion = 1;
        private Instant occurredAt;
        private Instant recordedAt;
        private UUID tenantId;
        private Book book;
        private String vertical;
        private String aggregateType;
        private UUID aggregateId;
        private long aggregateVersion;
        private long sequence;
        private UUID correlationId;
        private UUID causationId;
        private UUID transactionId;
        private EventActor actor;
        private EventSubject subject;
        private List<EventLink> links = new ArrayList<>();
        private Map<String, Object> data = new LinkedHashMap<>();
        private EventMetadata metadata;

        private Builder() {
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder eventVersion(int eventVersion) {
            this.eventVersion = eventVersion;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder recordedAt(Instant recordedAt) {
            this.recordedAt = recordedAt;
            return this;
        }

        public Builder tenantId(UUID tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder book(Book book) {
            this.book = book;
            return this;
        }

        public Builder vertical(String vertical) {
            this.vertical = vertical;
            return this;
        }

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder aggregateId(UUID aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder aggregateVersion(long aggregateVersion) {
            this.aggregateVersion = aggregateVersion;
            return this;
        }

        public Builder sequence(long sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder correlationId(UUID correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder causationId(UUID causationId) {
            this.causationId = causationId;
            return this;
        }

        public Builder transactionId(UUID transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder actor(EventActor actor) {
            this.actor = actor;
            return this;
        }

        public Builder subject(EventSubject subject) {
            this.subject = subject;
            return this;
        }

        public Builder links(List<EventLink> links) {
            this.links = links == null ? new ArrayList<>() : new ArrayList<>(links);
            return this;
        }

        public Builder data(Map<String, Object> data) {
            this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
            return this;
        }

        public Builder putData(String key, Object value) {
            this.data.put(key, value);
            return this;
        }

        public Builder metadata(EventMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public EventEnvelope build() {
            return new EventEnvelope(
                    eventId, eventType, eventVersion, occurredAt, recordedAt, tenantId, book,
                    vertical, aggregateType, aggregateId, aggregateVersion, sequence,
                    correlationId, causationId, transactionId, actor, subject, links, data, metadata);
        }
    }
}
