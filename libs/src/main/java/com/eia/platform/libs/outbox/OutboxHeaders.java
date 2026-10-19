package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventLink;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.util.List;
import java.util.UUID;

/**
 * Envelope members that are not first-class outbox columns. They travel in the
 * {@code headers} jsonb object so the outbox table stays close to SPEC 7.5.1
 * while still reconstructing the complete canonical envelope at dispatch time.
 */
public record OutboxHeaders(
        Book book,
        String vertical,
        UUID correlationId,
        UUID causationId,
        UUID transactionId,
        EventActor actor,
        EventSubject subject,
        List<EventLink> links,
        EventMetadata metadata
) {
    public OutboxHeaders {
        links = links == null ? List.of() : List.copyOf(links);
    }
}
