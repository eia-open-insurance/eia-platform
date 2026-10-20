package com.eia.platform.eventingest.api;

import java.util.List;

/**
 * Collection response for {@code GET /api/v1/events/recent}.
 */
public record RecentEventsResponse(List<EventView> items, int limit, int count) {
}
