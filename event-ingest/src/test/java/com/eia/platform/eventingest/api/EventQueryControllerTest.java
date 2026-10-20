package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.EventIngestProperties;
import com.eia.platform.eventingest.inbox.AggregateEvent;
import com.eia.platform.eventingest.inbox.EventTypeCount;
import com.eia.platform.eventingest.inbox.InboxEvent;
import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.InboxStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventQueryController.class)
class EventQueryControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final UUID NEWER_EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b91");
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000002");
    private static final UUID AGGREGATE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InboxService inbox;

    @MockitoBean
    private EventIngestProperties properties;

    @Test
    void recentReturnsDashboardViewsWithoutPayload() throws Exception {
        when(properties.effectiveMaxRecentLimit()).thenReturn(200);
        when(inbox.recent(20)).thenReturn(List.of(new InboxEvent(
                EVENT_ID,
                "party.party.created",
                TENANT_ID,
                "DIRECT",
                "Party",
                AGGREGATE_ID,
                1,
                1,
                NOW.minusSeconds(5),
                NOW,
                NOW,
                "PROCESSED",
                "hash")));

        mvc.perform(get("/api/v1/events/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].eventType").value("party.party.created"))
                .andExpect(jsonPath("$.items[0].book").value("DIRECT"))
                .andExpect(jsonPath("$.items[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void recentClampsTheLimitToTheConfiguredMaximum() throws Exception {
        when(properties.effectiveMaxRecentLimit()).thenReturn(50);
        when(inbox.recent(50)).thenReturn(List.of());

        mvc.perform(get("/api/v1/events/recent?limit=999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void statsReturnsCountsAndRecentEventTypes() throws Exception {
        when(inbox.stats(5)).thenReturn(new InboxStats(
                12,
                11,
                1,
                0,
                NOW.minusSeconds(2),
                NOW,
                List.of(new EventTypeCount("party.party.created", 7, NOW)),
                Map.of("DIRECT", 10L, "ASSUMED", 2L),
                NOW));

        mvc.perform(get("/api/v1/events/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(12))
                .andExpect(jsonPath("$.processedEvents").value(11))
                .andExpect(jsonPath("$.receivedEvents").value(1))
                .andExpect(jsonPath("$.recentEventTypes[0].eventType").value("party.party.created"))
                .andExpect(jsonPath("$.recentEventTypes[0].count").value(7))
                .andExpect(jsonPath("$.byBook.DIRECT").value(10));
    }

    @Test
    void aggregateReturnsTenantEventsNewestFirstWithJsonPayload() throws Exception {
        when(inbox.aggregateActivity(TENANT_ID, AGGREGATE_ID, 50)).thenReturn(List.of(
                aggregateEvent(NEWER_EVENT_ID, NOW, 2),
                aggregateEvent(EVENT_ID, NOW.minusSeconds(30), 1)));

        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateId").value(AGGREGATE_ID.toString()))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.items[0].eventId").value(NEWER_EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].eventType").value("policy.policy.issued"))
                .andExpect(jsonPath("$.items[0].eventVersion").value(1))
                .andExpect(jsonPath("$.items[0].tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.items[0].book").value("DIRECT"))
                .andExpect(jsonPath("$.items[0].aggregateType").value("Policy"))
                .andExpect(jsonPath("$.items[0].aggregateId").value(AGGREGATE_ID.toString()))
                .andExpect(jsonPath("$.items[0].aggregateVersion").value(2))
                .andExpect(jsonPath("$.items[0].occurredAt").value(NOW.toString()))
                .andExpect(jsonPath("$.items[0].receivedAt").value(NOW.plusSeconds(1).toString()))
                .andExpect(jsonPath("$.items[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$.items[0].payload.eventId").value(NEWER_EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].payload.data.policyNumber").value("POL-0001"))
                .andExpect(jsonPath("$.items[1].eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[1].aggregateVersion").value(1));

        verify(inbox).aggregateActivity(TENANT_ID, AGGREGATE_ID, 50);
    }

    @Test
    void aggregateUsesTheTenantFromTheRequestHeader() throws Exception {
        when(inbox.aggregateActivity(OTHER_TENANT_ID, AGGREGATE_ID, 50)).thenReturn(List.of());

        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        verify(inbox).aggregateActivity(OTHER_TENANT_ID, AGGREGATE_ID, 50);
    }

    @Test
    void aggregateAcceptsDefaultAndMaximumLimits() throws Exception {
        when(inbox.aggregateActivity(TENANT_ID, AGGREGATE_ID, 50)).thenReturn(List.of());
        when(inbox.aggregateActivity(TENANT_ID, AGGREGATE_ID, 100)).thenReturn(List.of());

        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50));

        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}?limit=100", AGGREGATE_ID)
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100));

        verify(inbox).aggregateActivity(TENANT_ID, AGGREGATE_ID, 50);
        verify(inbox).aggregateActivity(TENANT_ID, AGGREGATE_ID, 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "not-a-number"})
    void aggregateRejectsAnInvalidLimit(String limit) throws Exception {
        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .queryParam("limit", limit)
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4003"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));

        verifyNoInteractions(inbox);
    }

    @Test
    void aggregateRejectsAMissingTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4004"))
                .andExpect(jsonPath("$.errors[0].field").value("X-EIA-Tenant"));

        verifyNoInteractions(inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-uuid"})
    void aggregateRejectsAMalformedTenantHeader(String tenantHeader) throws Exception {
        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .header("X-EIA-Tenant", tenantHeader))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("X-EIA-Tenant"));

        verifyNoInteractions(inbox);
    }

    @Test
    void aggregateRejectsAMalformedAggregateId() throws Exception {
        mvc.perform(get("/api/v1/events/aggregate/not-a-uuid")
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4003"))
                .andExpect(jsonPath("$.errors[0].field").value("aggregateId"));

        verifyNoInteractions(inbox);
    }

    @Test
    void aggregateReturnsAnEmptyArrayWhenTheTenantHasNoActivity() throws Exception {
        when(inbox.aggregateActivity(TENANT_ID, AGGREGATE_ID, 50)).thenReturn(List.of());

        mvc.perform(get("/api/v1/events/aggregate/{aggregateId}", AGGREGATE_ID)
                        .header("X-EIA-Tenant", TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateId").value(AGGREGATE_ID.toString()))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private static AggregateEvent aggregateEvent(UUID eventId, Instant occurredAt, long aggregateVersion) {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("eventId", eventId.toString())
                .put("eventType", "policy.policy.issued")
                .put("aggregateId", AGGREGATE_ID.toString())
                .put("aggregateVersion", aggregateVersion);
        payload.set("data", MAPPER.createObjectNode().put("policyNumber", "POL-0001"));
        return new AggregateEvent(
                eventId,
                "policy.policy.issued",
                1,
                TENANT_ID,
                "DIRECT",
                "Policy",
                AGGREGATE_ID,
                aggregateVersion,
                occurredAt,
                occurredAt.plusSeconds(1),
                "PROCESSED",
                payload);
    }
}
