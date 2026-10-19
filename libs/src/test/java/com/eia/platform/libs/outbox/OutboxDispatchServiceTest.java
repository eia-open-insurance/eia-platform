package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxDispatchServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final Instant NOW = Instant.parse("2026-10-19T13:05:00Z");

    @Mock
    private OutboxRepository repository;

    @Mock
    private OutboxDispatcher dispatcher;

    private OutboxProperties properties;
    private OutboxDispatchService service;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setBatchSize(100);
        properties.setMaxAttempts(12);
        properties.setClaimLease(Duration.ofMinutes(2));
        service = new OutboxDispatchService(
                repository, dispatcher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksDeliveredEventsPublished() {
        OutboxEvent event = event("party.party.created", 1, Instant.parse("2026-10-19T13:04:11Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(dispatcher.dispatch(any())).thenReturn(OutboxDispatchResult.delivered());
        when(repository.markSent(event.id(), NOW)).thenReturn(true);

        assertThat(service.dispatchBatch()).isEqualTo(1);

        verify(repository).markSent(event.id(), NOW);
        verify(repository, never()).markFailed(any(), any(), any());
        verify(repository, never()).markDead(any(), any());
    }

    @Test
    void retrySchedulesTheFirstBackoff() {
        OutboxEvent event = event("party.party.created", 0, Instant.parse("2026-10-19T13:04:11Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(dispatcher.dispatch(any())).thenReturn(OutboxDispatchResult.retry("HTTP 503"));
        when(repository.markFailed(event.id(), "HTTP 503", NOW.plusSeconds(1))).thenReturn(true);

        assertThat(service.dispatchBatch()).isZero();

        verify(repository).markFailed(event.id(), "HTTP 503", NOW.plusSeconds(1));
        verify(repository, never()).markDead(any(), any());
    }

    @Test
    void exhaustedRetriesBecomeDead() {
        OutboxEvent event = event("party.party.created", 12, Instant.parse("2026-10-19T13:04:11Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(dispatcher.dispatch(any())).thenReturn(OutboxDispatchResult.retry("HTTP 503"));
        when(repository.markDead(event.id(), "HTTP 503")).thenReturn(true);

        assertThat(service.dispatchBatch()).isZero();

        verify(repository).markDead(event.id(), "HTTP 503");
        verify(repository, never()).markFailed(any(), any(), any());
    }

    @Test
    void permanentRejectionBecomesDeadImmediately() {
        OutboxEvent event = event("party.party.created", 0, Instant.parse("2026-10-19T13:04:11Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(dispatcher.dispatch(any())).thenReturn(OutboxDispatchResult.dead("HTTP 422"));
        when(repository.markDead(event.id(), "HTTP 422")).thenReturn(true);

        assertThat(service.dispatchBatch()).isZero();

        verify(repository).markDead(event.id(), "HTTP 422");
    }

    @Test
    void dispatcherExceptionIsTreatedAsTransient() {
        OutboxEvent event = event("party.party.created", 0, Instant.parse("2026-10-19T13:04:11Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(dispatcher.dispatch(any())).thenThrow(new IllegalStateException("socket closed"));
        when(repository.markFailed(event.id(), "Dispatcher failure: socket closed", NOW.plusSeconds(1)))
                .thenReturn(true);

        assertThat(service.dispatchBatch()).isZero();

        verify(repository).markFailed(
                event.id(), "Dispatcher failure: socket closed", NOW.plusSeconds(1));
    }

    @Test
    void aFailedEventBlocksItsPartitionSuccessors() {
        OutboxEvent first = event("party.party.created", 0, Instant.parse("2026-10-19T13:04:11Z"));
        OutboxEvent second = event("party.party.updated", 0, Instant.parse("2026-10-19T13:04:12Z"));
        when(repository.claimBatch(100, NOW, Duration.ofMinutes(2))).thenReturn(List.of(first, second));
        when(dispatcher.dispatch(any())).thenReturn(OutboxDispatchResult.retry("HTTP 503"));
        when(repository.markFailed(first.id(), "HTTP 503", NOW.plusSeconds(1))).thenReturn(true);
        when(repository.release(second.id(), NOW)).thenReturn(true);

        assertThat(service.dispatchBatch()).isZero();

        verify(dispatcher, times(1)).dispatch(any());
        verify(repository).release(second.id(), NOW);
        verify(repository, never()).markSent(second.id(), NOW);
    }

    private static OutboxEvent event(String eventType, int attempts, Instant recordedAt) {
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(recordedAt)
                .recordedAt(recordedAt)
                .tenantId(TENANT_ID)
                .book(Book.DIRECT)
                .vertical("party")
                .aggregateType("Party")
                .aggregateId(PARTY_ID)
                .aggregateVersion(1)
                .actor(EventActor.system("eia-party-expert"))
                .subject(EventSubject.of("party", "party", PARTY_ID, "PTY-000004281"))
                .data(Map.of("partyId", PARTY_ID))
                .metadata(EventMetadata.v1("eia-party-expert@0.1.0"))
                .build();
        return OutboxEvent.fromEnvelope(envelope).withSequence(1).withAttempts(attempts);
    }
}
