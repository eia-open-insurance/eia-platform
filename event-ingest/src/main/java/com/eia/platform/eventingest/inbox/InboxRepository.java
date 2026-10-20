package com.eia.platform.eventingest.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for {@code inbox_event} (SPEC 7.5.2).
 *
 * <p>The primary key on {@code event_id} is the idempotency guard: the insert
 * uses {@code ON CONFLICT (event_id) DO NOTHING} and a zero row count means the
 * event was already received.</p>
 */
@Repository
public class InboxRepository {

    private static final String COLUMNS = """
            event_id, event_type, tenant_id, business_book, aggregate_type, aggregate_id,
            aggregate_version, event_version, occurred_at, received_at, processed_at,
            status, payload_hash
            """;

    private static final RowMapper<InboxEvent> MAPPER = InboxRepository::map;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<AggregateEvent> aggregateMapper;

    public InboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.aggregateMapper = this::mapAggregate;
    }

    /**
     * Inserts the event when its id is new.
     *
     * @return {@code true} when this delivery created the row, {@code false}
     *         when the id already exists
     */
    public boolean insertIfAbsent(CanonicalEvent event, String headersJson, Instant receivedAt) {
        int inserted = jdbc.update("""
                INSERT INTO inbox_event (
                    event_id, event_type, tenant_id, business_book, aggregate_type, aggregate_id,
                    aggregate_version, event_version, occurred_at, received_at, processed_at,
                    status, payload, headers, payload_hash
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, NULL,
                    'RECEIVED', CAST(? AS jsonb), CAST(? AS jsonb), ?
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.eventType(),
                event.tenantId(),
                event.book(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.eventVersion(),
                java.sql.Timestamp.from(event.occurredAt()),
                java.sql.Timestamp.from(receivedAt),
                event.canonicalJson(),
                headersJson,
                event.payloadHash());
        return inserted == 1;
    }

    /**
     * Marks the inbox row processed. Called in the same transaction as the
     * insert, mirroring the consumer algorithm in SPEC 7.5.2.
     */
    public void markProcessed(UUID eventId, Instant processedAt) {
        jdbc.update("""
                UPDATE inbox_event
                   SET status = 'PROCESSED', processed_at = ?
                 WHERE event_id = ? AND status = 'RECEIVED'
                """, java.sql.Timestamp.from(processedAt), eventId);
    }

    public Optional<InboxEvent> findById(UUID eventId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inbox_event WHERE event_id = ?",
                MAPPER, eventId).stream().findFirst();
    }

    public List<InboxEvent> recent(int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM inbox_event
                 ORDER BY received_at DESC, event_id DESC
                 LIMIT ?
                """.formatted(COLUMNS), MAPPER, limit);
    }

    /**
     * Newest-first events for one aggregate, always constrained to the
     * caller's tenant. The {@code (tenant_id, aggregate_id, occurred_at DESC)}
     * index backs this read (schema.sql).
     */
    public List<AggregateEvent> findByAggregate(UUID tenantId, UUID aggregateId, int limit) {
        return jdbc.query("""
                SELECT event_id, event_type, event_version, tenant_id, business_book,
                       aggregate_type, aggregate_id, aggregate_version, occurred_at,
                       received_at, status, payload
                  FROM inbox_event
                 WHERE tenant_id = ? AND aggregate_id = ?
                 ORDER BY occurred_at DESC, event_id DESC
                 LIMIT ?
                """, aggregateMapper, tenantId, aggregateId, limit);
    }

    public InboxStats stats(int eventTypeLimit) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS total_events,
                       count(*) FILTER (WHERE status = 'PROCESSED') AS processed_events,
                       count(*) FILTER (WHERE status = 'RECEIVED') AS received_events,
                       count(*) FILTER (WHERE status IN ('FAILED', 'DEAD_LETTERED')) AS failed_events,
                       max(occurred_at) AS last_event_at,
                       max(received_at) AS last_received_at
                  FROM inbox_event
                """);

        List<EventTypeCount> eventTypes = jdbc.query("""
                SELECT event_type, count(*) AS event_count, max(received_at) AS last_seen_at
                  FROM inbox_event
                 GROUP BY event_type
                 ORDER BY last_seen_at DESC, event_type
                 LIMIT ?
                """, (rs, rowNum) -> new EventTypeCount(
                rs.getString("event_type"),
                rs.getLong("event_count"),
                instant(rs, "last_seen_at")), eventTypeLimit);

        Map<String, Long> byBook = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT business_book, count(*) AS event_count
                  FROM inbox_event
                 GROUP BY business_book
                 ORDER BY business_book
                """)) {
            byBook.put(String.valueOf(row.get("business_book")),
                    ((Number) row.get("event_count")).longValue());
        }

        return new InboxStats(
                longValue(totals.get("total_events")),
                longValue(totals.get("processed_events")),
                longValue(totals.get("received_events")),
                longValue(totals.get("failed_events")),
                instantValue(totals.get("last_event_at")),
                instantValue(totals.get("last_received_at")),
                List.copyOf(eventTypes),
                Collections.unmodifiableMap(byBook),
                Instant.now());
    }

    private static InboxEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new InboxEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_book"),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getLong("aggregate_version"),
                rs.getInt("event_version"),
                instant(rs, "occurred_at"),
                instant(rs, "received_at"),
                instant(rs, "processed_at"),
                rs.getString("status"),
                rs.getString("payload_hash"));
    }

    private AggregateEvent mapAggregate(ResultSet rs, int rowNum) throws SQLException {
        return new AggregateEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_book"),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getLong("aggregate_version"),
                instant(rs, "occurred_at"),
                instant(rs, "received_at"),
                rs.getString("status"),
                json(rs, "payload"));
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored " + column + " is not valid JSON", ex);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass());
    }

    private static long longValue(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
