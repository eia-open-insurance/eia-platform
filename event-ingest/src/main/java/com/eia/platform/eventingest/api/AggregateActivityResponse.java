package com.eia.platform.eventingest.api;

import java.util.List;
import java.util.UUID;

/**
 * Collection response for {@code GET /api/v1/events/aggregate/{aggregateId}}.
 * Items are newest first by {@code occurredAt}.
 */
public record AggregateActivityResponse(UUID aggregateId, int limit, List<AggregateEventView> items) {
}
