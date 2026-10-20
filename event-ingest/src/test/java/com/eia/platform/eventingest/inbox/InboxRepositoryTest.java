package com.eia.platform.eventingest.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the aggregate-activity query against an in-memory PostgreSQL-mode
 * database. This covers the behaviour that a controller mock cannot: the
 * tenant predicate, newest-first ordering, the row limit, and JSON payload
 * decoding.
 */
class InboxRepositoryTest {

    private static final String EVENT_TYPE = "party.party.created";
    private static final Instant BASE = Instant.parse("2026-10-20T09:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private InboxRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inbox_repository_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE inbox_event (
                    event_id UUID PRIMARY KEY,
                    event_type VARCHAR(96) NOT NULL,
                    tenant_id UUID NOT NULL,
                    business_book VARCHAR(16) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id UUID NOT NULL,
                    aggregate_version BIGINT NOT NULL,
                    event_version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    payload JSON NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS ix_inbox_event_tenant_aggregate_occurred
                    ON inbox_event (tenant_id, aggregate_id, occurred_at DESC)
                """);
        repository = new InboxRepository(jdbc, mapper);
    }

    @Test
    void returnsOnlyTheRequestedTenantsEventsForTheAggregateNewestFirst() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID otherTenantEvent = UUID.randomUUID();

        insert(older, tenantId, aggregateId, 1, BASE);
        insert(newest, tenantId, aggregateId, 3, BASE.plusSeconds(30));
        insert(middle, tenantId, aggregateId, 2, BASE.plusSeconds(10));
        insert(otherTenantEvent, otherTenantId, aggregateId, 4, BASE.plusSeconds(40));
        insert(UUID.randomUUID(), tenantId, UUID.randomUUID(), 5, BASE.plusSeconds(50));

        List<AggregateEvent> events = repository.findByAggregate(tenantId, aggregateId, 50);

        assertThat(events).extracting(AggregateEvent::eventId)
                .containsExactly(newest, middle, older);
        assertThat(events).extracting(AggregateEvent::tenantId)
                .containsOnly(tenantId);
        assertThat(events).extracting(AggregateEvent::aggregateId)
                .containsOnly(aggregateId);

        AggregateEvent event = events.get(0);
        assertThat(event.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.book()).isEqualTo("DIRECT");
        assertThat(event.aggregateType()).isEqualTo("Party");
        assertThat(event.aggregateVersion()).isEqualTo(3);
        assertThat(event.occurredAt()).isEqualTo(BASE.plusSeconds(30));
        assertThat(event.receivedAt()).isEqualTo(BASE.plusSeconds(31));
        assertThat(event.status()).isEqualTo("PROCESSED");

        assertThat(repository.findByAggregate(otherTenantId, aggregateId, 50))
                .extracting(AggregateEvent::eventId)
                .containsExactly(otherTenantEvent);
    }

    @Test
    void appliesTheLimitAfterNewestFirstOrdering() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();

        insert(older, tenantId, aggregateId, 1, BASE);
        insert(middle, tenantId, aggregateId, 2, BASE.plusSeconds(10));
        insert(newest, tenantId, aggregateId, 3, BASE.plusSeconds(20));

        assertThat(repository.findByAggregate(tenantId, aggregateId, 2))
                .extracting(AggregateEvent::eventId)
                .containsExactly(newest, middle);
    }

    @Test
    void returnsAnEmptyListWhenTheTenantOrAggregateHasNoEvents() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        insert(UUID.randomUUID(), tenantId, aggregateId, 1, BASE);

        assertThat(repository.findByAggregate(tenantId, UUID.randomUUID(), 50)).isEmpty();
        assertThat(repository.findByAggregate(UUID.randomUUID(), aggregateId, 50)).isEmpty();
    }

    @Test
    void decodesTheStoredPayloadAsJson() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insert(eventId, tenantId, aggregateId, 7, BASE);

        JsonNode payload = repository.findByAggregate(tenantId, aggregateId, 50)
                .get(0)
                .payload();

        assertThat(payload.isObject()).isTrue();
        assertThat(payload.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(payload.path("aggregateId").asText()).isEqualTo(aggregateId.toString());
        assertThat(payload.path("data").path("partyNumber").asText()).isEqualTo("PTY-7");
    }

    private void insert(UUID eventId, UUID tenantId, UUID aggregateId, long aggregateVersion,
                        Instant occurredAt) {
        jdbc.update("""
                INSERT INTO inbox_event (
                    event_id, event_type, tenant_id, business_book, aggregate_type, aggregate_id,
                    aggregate_version, event_version, occurred_at, received_at, status, payload
                ) VALUES (
                    ?, ?, ?, 'DIRECT', 'Party', ?, ?, 1, ?, ?, 'PROCESSED', ? FORMAT JSON
                )
                """,
                eventId,
                EVENT_TYPE,
                tenantId,
                aggregateId,
                aggregateVersion,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(occurredAt.plusSeconds(1), ZoneOffset.UTC),
                "{\"eventId\":\"" + eventId + "\",\"aggregateId\":\"" + aggregateId
                        + "\",\"data\":{\"partyNumber\":\"PTY-" + aggregateVersion + "\"}}");
    }
}
