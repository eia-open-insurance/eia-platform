package com.eia.platform.eventingest.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted {@code inbox_event} row without its JSON payload.
 */
public record InboxEvent(
        UUID eventId,
        String eventType,
        UUID tenantId,
        String book,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        int eventVersion,
        Instant occurredAt,
        Instant receivedAt,
        Instant processedAt,
        String status,
        String payloadHash
) {
}
