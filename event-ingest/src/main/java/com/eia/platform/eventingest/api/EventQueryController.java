package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.EventIngestProperties;
import com.eia.platform.eventingest.inbox.InboxEvent;
import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.InboxStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read endpoints used by the shell operations dashboard.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventQueryController {

    private static final int DEFAULT_LIMIT = 20;

    private final InboxService inbox;
    private final EventIngestProperties properties;

    public EventQueryController(InboxService inbox, EventIngestProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
    }

    @GetMapping("/recent")
    public RecentEventsResponse recent(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int effective = Math.max(1, Math.min(limit, properties.effectiveMaxRecentLimit()));
        List<EventView> items = inbox.recent(effective).stream()
                .map(EventQueryController::view)
                .toList();
        return new RecentEventsResponse(items, effective, items.size());
    }

    @GetMapping("/stats")
    public InboxStats stats() {
        return inbox.stats(5);
    }

    private static EventView view(InboxEvent event) {
        return new EventView(
                event.eventId(),
                event.eventType(),
                event.tenantId(),
                event.book(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.eventVersion(),
                event.occurredAt(),
                event.receivedAt(),
                event.processedAt(),
                event.status());
    }
}
