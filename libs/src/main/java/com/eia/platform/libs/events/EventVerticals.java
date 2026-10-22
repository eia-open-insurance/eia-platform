package com.eia.platform.libs.events;

import java.util.Set;

/**
 * Verticals allowed in the canonical event envelope. Claim events use
 * {@code direct} and {@code assumed} even though their HTTP path segments are
 * {@code direct-claims} and {@code assumed-claims}.
 */
public final class EventVerticals {
    public static final Set<String> ALL = Set.of(
            "party",
            "policy",
            "contract",
            "direct",
            "assumed",
            "document",
            "payment",
            "notifications",
            "cession",
            "retrocession",
            "product");

    private EventVerticals() {
    }

    public static String require(String vertical) {
        if (vertical == null || !ALL.contains(vertical)) {
            throw new IllegalArgumentException("Unknown event vertical: " + vertical);
        }
        return vertical;
    }
}
