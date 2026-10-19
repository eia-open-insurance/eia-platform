package com.eia.platform.libs.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-vertical reference used by event links (EV-04).
 */
public record EventTarget(String vertical, String type, UUID id) {
    public EventTarget {
        EventVerticals.require(vertical);
        if (type == null || type.isBlank() || type.length() > 64) {
            throw new IllegalArgumentException("Link target type must be 1..64 characters");
        }
        Objects.requireNonNull(id, "id");
    }
}
