package com.eia.platform.eventingest.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Dashboard view of one inbox event. The event payload is deliberately not
 * exposed: it may carry claim or party detail (SPEC EV-07).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventView(
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
        String status
) {
}
