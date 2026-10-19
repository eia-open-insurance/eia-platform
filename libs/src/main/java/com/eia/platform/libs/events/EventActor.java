package com.eia.platform.libs.events;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Actor that caused the event, matching the canonical envelope actor object.
 */
public record EventActor(
        String type,
        UUID id,
        String displayName,
        UUID delegationId,
        String authStrength
) {
    public static final Set<String> TYPES = Set.of("USER", "SYSTEM", "SCHEDULE", "INTEGRATION");

    public EventActor {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported actor type: " + type);
        }
        if (displayName != null && displayName.length() > 200) {
            throw new IllegalArgumentException("Actor displayName exceeds 200 characters");
        }
        if (authStrength != null && authStrength.length() > 32) {
            throw new IllegalArgumentException("Actor authStrength exceeds 32 characters");
        }
    }

    public static EventActor system(String displayName) {
        return new EventActor("SYSTEM", null, displayName, null, null);
    }

    public static EventActor user(UUID id, String displayName) {
        return new EventActor("USER", Objects.requireNonNull(id, "id"), displayName, null, null);
    }

    public static EventActor schedule(String displayName) {
        return new EventActor("SCHEDULE", null, displayName, null, null);
    }
}
