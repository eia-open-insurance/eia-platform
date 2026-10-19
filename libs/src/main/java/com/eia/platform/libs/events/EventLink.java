package com.eia.platform.libs.events;

import java.util.Objects;

/**
 * Newly created relationship carried by an event so lineage can be projected
 * without a callback (EV-04).
 */
public record EventLink(String linkType, EventTarget target) {
    public EventLink {
        if (linkType == null || linkType.isBlank() || linkType.length() > 64) {
            throw new IllegalArgumentException("linkType must be 1..64 characters");
        }
        Objects.requireNonNull(target, "target");
    }
}
