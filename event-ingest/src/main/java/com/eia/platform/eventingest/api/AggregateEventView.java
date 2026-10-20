package com.eia.platform.eventingest.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One event on an aggregate activity timeline.
 *
 * <p>{@code payload} is the canonical event envelope stored with the inbox row
 * and is embedded as JSON, not as an escaped string.</p>
 */
public record AggregateEventView(
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
