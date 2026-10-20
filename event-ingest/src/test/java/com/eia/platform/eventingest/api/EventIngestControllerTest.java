package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.inbox.EnvelopeValidationException;
import com.eia.platform.eventingest.inbox.EventConflictException;
import com.eia.platform.eventingest.inbox.InboxService;
import com.eia.platform.eventingest.inbox.IngestResult;
import com.eia.platform.eventingest.inbox.InvalidSignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventIngestController.class)
class EventIngestControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90");
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    private static final String BODY = "{\"eventId\":\"0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InboxService inbox;

    @Test
    void firstDeliveryReturns202() throws Exception {
        when(inbox.ingest(any(), any())).thenReturn(new IngestResult(EVENT_ID, false, NOW, NOW));

        mvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void identicalReplayReturns200WithReplayedTrue() throws Exception {
        when(inbox.ingest(any(), any())).thenReturn(new IngestResult(EVENT_ID, true, NOW, NOW));

        mvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void conflictingPayloadReturns409Problem() throws Exception {
        when(inbox.ingest(any(), any())).thenThrow(new EventConflictException(EVENT_ID));

        mvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4090"))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.meta.eventId").value(EVENT_ID.toString()));
    }

    @Test
    void badSignatureReturns401() throws Exception {
        when(inbox.ingest(any(), any())).thenThrow(new InvalidSignatureException());

        mvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4010"))
                .andExpect(jsonPath("$.category").value("AUTHENTICATION"));
    }

    @Test
    void invalidEnvelopeReturns400WithFieldIssues() throws Exception {
        when(inbox.ingest(any(), any())).thenThrow(new EnvelopeValidationException(List.of(
                new FieldIssue("tenantId", "REQUIRED", "tenantId is required"),
                new FieldIssue("data", "INVALID", "data must be a JSON object"))));

        mvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-PLT-4001"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"))
                .andExpect(jsonPath("$.errors[1].field").value("data"));
    }
}
