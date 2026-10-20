package com.eia.platform.eventingest.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated canonical event envelope.
 *
 * <p>{@code canonicalJson} is the envelope re-serialized with object keys sorted
 * so a retry that differs only in whitespace or member order hashes the same.
 * {@code payloadHash} is the SHA-256 of those canonical bytes and is the
 * idempotency-conflict discriminator.</p>
 */
public record CanonicalEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID tenantId,
        String book,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String canonicalJson,
        String payloadHash
) {
}
