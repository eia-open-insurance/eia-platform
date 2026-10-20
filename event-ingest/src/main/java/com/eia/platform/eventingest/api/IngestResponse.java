package com.eia.platform.eventingest.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for {@code POST /internal/events}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestResponse(
        UUID eventId,
        String status,
        boolean replayed,
        Instant receivedAt,
        Instant processedAt
) {
}
