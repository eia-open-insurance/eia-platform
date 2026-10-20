package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.IngestResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal inbox endpoint called by expert-service outbox dispatchers
 * (SPEC 7.5.1). It is a cluster-only route: it is not exposed through the
 * gateway or the Ingress, and a NetworkPolicy limits callers to pods in the
 * deployment namespace.
 *
 * <p>202 means the event was stored for the first time, 200 means an identical
 * replay, 409 means the id exists with a different payload, and 401 means the
 * HMAC signature is missing or invalid.</p>
 */
@RestController
public class EventIngestController {

    private final InboxService inbox;

    public EventIngestController(InboxService inbox) {
        this.inbox = inbox;
    }

    @PostMapping(
            path = "/internal/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingest(
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        IngestResult result = inbox.ingest(body, headers);
        IngestResponse response = new IngestResponse(
                result.eventId(),
                result.replayed() ? "REPLAYED" : "ACCEPTED",
                result.replayed(),
                result.receivedAt(),
                result.processedAt());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
