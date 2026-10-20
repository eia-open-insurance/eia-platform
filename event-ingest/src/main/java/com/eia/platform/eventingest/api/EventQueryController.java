package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.EventIngestProperties;
import com.eia.platform.eventingest.inbox.AggregateEvent;
import com.eia.platform.eventingest.inbox.InboxEvent;
import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.InboxStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read endpoints used by the shell operations dashboard.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventQueryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int DEFAULT_AGGREGATE_LIMIT = 50;
    private static final int MAX_AGGREGATE_LIMIT = 100;

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

    /**
     * Newest-first activity for one aggregate, scoped to the tenant in
     * {@code X-EIA-Tenant}. A malformed or missing tenant is a 400, as is a
     * UUID path that cannot be parsed and a {@code limit} outside 1..100.
     */
    @GetMapping("/aggregate/{aggregateId}")
    public AggregateActivityResponse aggregate(
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @PathVariable UUID aggregateId,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_AGGREGATE_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_AGGREGATE_LIMIT) {
            throw new InvalidLimitException(effectiveLimit, MAX_AGGREGATE_LIMIT);
        }
        List<AggregateEventView> items = inbox.aggregateActivity(tenantId, aggregateId, effectiveLimit)
                .stream()
                .map(EventQueryController::aggregateView)
                .toList();
        return new AggregateActivityResponse(aggregateId, effectiveLimit, items);
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

    private static AggregateEventView aggregateView(AggregateEvent event) {
        return new AggregateEventView(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.tenantId(),
                event.book(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.occurredAt(),
                event.receivedAt(),
                event.status(),
                event.payload());
    }
}
