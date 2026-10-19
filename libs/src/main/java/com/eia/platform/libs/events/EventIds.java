package com.eia.platform.libs.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Event identity helpers.
 *
 * <p>Outbox events use a deterministic UUIDv5 derived from the aggregate
 * identity, aggregate version, and event type. Retrying the same state change
 * therefore produces the same {@code eventId}, and the outbox unique key plus
 * {@code ON CONFLICT DO NOTHING} makes the append idempotent. A republished
 * event keeps its original id (EV-10).</p>
 */
public final class EventIds {
    /**
     * Fixed namespace for EIA platform event ids. Changing it changes every
     * derived id, so treat it as part of the event contract.
     */
    private static final UUID EIA_EVENT_NAMESPACE =
            UUID.fromString("7f3c1a52-9b6e-5a1d-9c2f-6d8e4b0a7c11");

    private EventIds() {
    }

    public static UUID deterministic(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(eventType, "eventType");
        String name = tenantId + "|" + aggregateType + "|" + aggregateId + "|"
                + aggregateVersion + "|" + eventType;
        return uuidV5(EIA_EVENT_NAMESPACE, name);
    }

    /**
     * RFC 4122 name-based UUID version 5 (SHA-1). Exposed for deterministic
     * identifiers outside the aggregate convention.
     */
    public static UUID uuidV5(UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is required by the JVM", ex);
        }
        digest.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(hash);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
