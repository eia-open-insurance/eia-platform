package com.eia.platform.libs.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Subject of an event, matching the canonical envelope subject object.
 */
public record EventSubject(String vertical, String type, UUID id, String businessKey) {
    public EventSubject {
        EventVerticals.require(vertical);
        if (type == null || type.isBlank() || type.length() > 64) {
            throw new IllegalArgumentException("Subject type must be 1..64 characters");
        }
        Objects.requireNonNull(id, "id");
        if (businessKey != null && businessKey.length() > 64) {
            throw new IllegalArgumentException("Subject businessKey exceeds 64 characters");
        }
    }

    public static EventSubject of(String vertical, String type, UUID id, String businessKey) {
        return new EventSubject(vertical, type, id, businessKey);
    }
}
