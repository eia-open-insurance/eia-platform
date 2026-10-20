package com.eia.platform.eventingest.inbox;

import java.time.Instant;

/**
 * Event type frequency and freshness, used by the shell Event stream card.
 */
public record EventTypeCount(String eventType, long count, Instant lastSeenAt) {
}
