package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.EventIngestProperties;
import com.eia.platform.eventingest.inbox.EventTypeCount;
import com.eia.platform.eventingest.inbox.InboxEvent;
import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.InboxStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventQueryController.class)
class EventQueryControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

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
                UUID.fromString("0192f3a4-0000-7000-8000-000000000001"),
                "DIRECT",
                "Party",
                UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21"),
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
}
