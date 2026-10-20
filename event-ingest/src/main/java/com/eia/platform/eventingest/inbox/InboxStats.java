package com.eia.platform.eventingest.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate inbox statistics for {@code GET /api/v1/events/stats}.
 */
public record InboxStats(
        long totalEvents,
        long processedEvents,
        long receivedEvents,
        long failedEvents,
        Instant lastEventAt,
        Instant lastReceivedAt,
        List<EventTypeCount> recentEventTypes,
        Map<String, Long> byBook,
        Instant generatedAt
) {
}
