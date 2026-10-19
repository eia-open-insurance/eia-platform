package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRepositoryTest {
    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T13:04:11.240Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-10-19T13:04:11.512Z");
    private static final Instant NOW = Instant.parse("2026-10-19T13:05:00Z");

    @Mock
    private JdbcTemplate jdbc;

    private OutboxRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OutboxRepository(jdbc);
    }

    @Test
    void claimBatchUsesSkipLockedAndLeasesClaimedRows() {
        OutboxEvent pending = pendingEvent();
        when(jdbc.update(contains("SET status = 'PENDING'"), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxEvent>>any(),
                any(java.sql.Timestamp.class), eq(100)))
                .thenReturn(List.of(pending));
        when(jdbc.batchUpdate(contains("SET status = 'IN_FLIGHT'"), anyList()))
                .thenReturn(new int[]{1});

        List<OutboxEvent> claimed = repository.claimBatch(100, NOW, Duration.ofMinutes(2));

        assertThat(claimed).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(OutboxEvent.Status.IN_FLIGHT);
            assertThat(event.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
            assertThat(event.sequence()).isEqualTo(7);
            assertThat(event.attempts()).isZero();
        });

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxEvent>>any(),
                any(java.sql.Timestamp.class), eq(100));
        assertThat(sql.getValue())
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT ?")
                .contains("status = 'PENDING'");
        verify(jdbc).batchUpdate(contains("SET status = 'IN_FLIGHT'"), anyList());
        verify(jdbc).update(contains("SET status = 'PENDING'"),
                any(java.sql.Timestamp.class), any(java.sql.Timestamp.class));
    }

    @Test
    void insertIsIdempotentAndUsesThePostgresJsonCast() {
        OutboxEvent event = pendingEvent();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        boolean inserted = repository.insert(event);

        assertThat(inserted).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("ON CONFLICT DO NOTHING")
                .contains("CAST(? AS JSON)");
        assertThat(args.getValue()).hasSize(14);
        assertThat(args.getValue()[0]).isEqualTo(event.id());
        assertThat(args.getValue()[7]).isEqualTo(event.payload());
        assertThat(args.getValue()[8]).isEqualTo(event.headers());
    }

    @Test
    void markSentPublishesAndStampsSentAt() {
        UUID id = UUID.randomUUID();
        Instant sentAt = NOW.plusSeconds(3);
        when(jdbc.update(contains("status = 'PUBLISHED'"), any(java.sql.Timestamp.class), eq(id)))
                .thenReturn(1);

        assertThat(repository.markSent(id, sentAt)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(java.sql.Timestamp.class), eq(id));
        assertThat(sql.getValue())
                .contains("status = 'PUBLISHED'")
                .contains("sent_at = ?")
                .contains("last_error = NULL");
    }

    @Test
    void markFailedIncrementsAttemptsAndSchedulesNextAttempt() {
        UUID id = UUID.randomUUID();
        Instant nextAttemptAt = NOW.plusSeconds(5);
        when(jdbc.update(contains("attempts = attempts + 1"),
                any(java.sql.Timestamp.class), eq("HTTP 503"), eq(id)))
                .thenReturn(1);

        assertThat(repository.markFailed(id, "HTTP 503", nextAttemptAt)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(java.sql.Timestamp.class), eq("HTTP 503"), eq(id));
        assertThat(sql.getValue())
                .contains("SET status = 'PENDING'")
                .contains("attempts = attempts + 1")
                .contains("next_attempt_at = ?")
                .contains("last_error = ?");
    }

    @Test
    void markDeadRecordsTheErrorWithoutSchedulingAnotherAttempt() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(contains("status = 'DEAD'"), eq("permanent rejection"), eq(id)))
                .thenReturn(1);

        assertThat(repository.markDead(id, "permanent rejection")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("permanent rejection"), eq(id));
        assertThat(sql.getValue())
                .contains("status = 'DEAD'")
                .doesNotContain("next_attempt_at = ?");
    }

    private static OutboxEvent pendingEvent() {
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(UUID.fromString("0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90"))
                .eventType("party.party.created")
                .eventVersion(1)
                .occurredAt(OCCURRED_AT)
                .recordedAt(RECORDED_AT)
                .tenantId(TENANT_ID)
                .book(Book.DIRECT)
                .vertical("party")
                .aggregateType("Party")
                .aggregateId(PARTY_ID)
                .aggregateVersion(1)
                .actor(EventActor.system("eia-party-expert"))
                .subject(EventSubject.of("party", "party", PARTY_ID, "PTY-000004281"))
                .data(java.util.Map.of("partyNumber", "PTY-000004281"))
                .metadata(EventMetadata.v1("eia-party-expert@0.1.0"))
                .build();
        return OutboxEvent.fromEnvelope(envelope).withSequence(7);
    }
}
