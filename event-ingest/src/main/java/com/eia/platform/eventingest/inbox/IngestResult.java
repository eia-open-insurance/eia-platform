package com.eia.platform.eventingest.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of an ingest attempt.
 *
 * <p>{@code replayed=false} is a first delivery (HTTP 202);
 * {@code replayed=true} is an idempotent replay with an identical payload
 * (HTTP 200).</p>
 */
public record IngestResult(UUID eventId, boolean replayed, Instant receivedAt, Instant processedAt) {

    public static IngestResult accepted(CanonicalEvent event, Instant receivedAt, Instant processedAt) {
        return new IngestResult(event.eventId(), false, receivedAt, processedAt);
    }

    public static IngestResult replayed(InboxEvent existing) {
        return new IngestResult(existing.eventId(), true, existing.receivedAt(), existing.processedAt());
    }
}
