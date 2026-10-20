package com.eia.platform.eventingest.inbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored event on an aggregate timeline, including its JSON payload.
 *
 * <p>{@code payload} is the canonical envelope persisted in
 * {@code inbox_event.payload}; it is kept as a {@link JsonNode} so an HTTP
 * response embeds it as JSON rather than an escaped string.</p>
 */
public record AggregateEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        UUID tenantId,
        String book,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        Instant receivedAt,
        String status,
        JsonNode payload
) {
}
