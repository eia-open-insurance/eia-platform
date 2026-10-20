package com.eia.platform.eventingest.inbox;

import java.util.UUID;

/**
 * The same {@code eventId} was already stored with a different payload
 * (HTTP 409). Events are immutable (SPEC EV-09); a corrected fact needs a new
 * event id (EV-10).
 */
public class EventConflictException extends RuntimeException {

    private final transient UUID eventId;

    public EventConflictException(UUID eventId) {
        super("eventId " + eventId + " was already ingested with a different payload");
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
