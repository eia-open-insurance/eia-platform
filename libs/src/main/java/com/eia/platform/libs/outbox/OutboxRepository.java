package com.eia.platform.libs.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring JDBC persistence for the transactional outbox (SPEC 7.5.1).
 *
 * <p>Claims use {@code SELECT ... FOR UPDATE SKIP LOCKED} inside a transaction,
 * then mark the selected rows IN_FLIGHT. Each claim sets a visibility lease in
 * {@code next_attempt_at}; a crashed dispatcher's rows become reclaimable when
 * the lease expires. Successful delivery marks PUBLISHED, a transient failure
 * increments attempts and schedules the next attempt, and a permanent failure
 * marks DEAD.</p>
 */
@Repository
@Transactional
public class OutboxRepository {
    private static final String COLUMNS = """
            id, event_sequence, tenant_id, aggregate_type, aggregate_id, aggregate_version,
            event_type, event_version, payload, headers, partition_key,
            occurred_at, recorded_at, next_attempt_at, status, attempts, last_error,
            created_at, sent_at
            """;

    private static final String INSERT_SQL_TEMPLATE = """
            INSERT INTO outbox_event (
                id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                event_type, event_version, payload, headers, partition_key,
                occurred_at, recorded_at, next_attempt_at, status, attempts, created_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, %s, %s, ?,
                ?, ?, ?, 'PENDING', 0, ?
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String RECLAIM_SQL = """
            UPDATE outbox_event
               SET status = 'PENDING', next_attempt_at = ?
             WHERE status = 'IN_FLIGHT' AND next_attempt_at <= ?
            """;

    private static final String SELECT_CLAIMABLE_SQL = """
            SELECT %s
              FROM outbox_event
             WHERE status = 'PENDING' AND next_attempt_at <= ?
             ORDER BY created_at, event_sequence
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """.formatted(COLUMNS);

    private static final String MARK_IN_FLIGHT_SQL = """
            UPDATE outbox_event
               SET status = 'IN_FLIGHT', next_attempt_at = ?
             WHERE id = ? AND status = 'PENDING'
            """;

    private static final String MARK_SENT_SQL = """
            UPDATE outbox_event
               SET status = 'PUBLISHED', sent_at = ?, last_error = NULL
             WHERE id = ? AND status = 'IN_FLIGHT'
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE outbox_event
               SET status = 'PENDING', attempts = attempts + 1,
                   next_attempt_at = ?, last_error = ?
             WHERE id = ? AND status = 'IN_FLIGHT'
            """;

    private static final String MARK_DEAD_SQL = """
            UPDATE outbox_event
               SET status = 'DEAD', attempts = attempts + 1, last_error = ?
             WHERE id = ? AND status = 'IN_FLIGHT'
            """;

    private static final String RELEASE_SQL = """
            UPDATE outbox_event
               SET status = 'PENDING', next_attempt_at = ?
             WHERE id = ? AND status = 'IN_FLIGHT'
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT %s FROM outbox_event WHERE id = ?
            """.formatted(COLUMNS);

    private static final RowMapper<OutboxEvent> ROW_MAPPER = OutboxRepository::mapRow;

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public OutboxRepository(JdbcTemplate jdbc) {
        this(jdbc, detectJsonParameter(jdbc));
    }

    /**
     * Visible for tests that need to force a dialect. Production code should
     * use the single-argument constructor.
     */
    public OutboxRepository(JdbcTemplate jdbc, String jsonParameter) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.insertSql = INSERT_SQL_TEMPLATE.formatted(
                Objects.requireNonNull(jsonParameter, "jsonParameter"),
                jsonParameter);
    }

    /**
     * Appends the event in the caller's transaction. The deterministic event id
     * plus the aggregate-version unique key make retries idempotent.
     *
     * @return true when a row was inserted, false for a duplicate append
     */
    public boolean insert(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        Object[] args = {
                event.id(),
                event.tenantId(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.eventType(),
                event.eventVersion(),
                event.payload(),
                event.headers(),
                event.partitionKey(),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.recordedAt()),
                Timestamp.from(event.nextAttemptAt()),
                Timestamp.from(event.createdAt())
        };
        return jdbc.update(insertSql, args) == 1;
    }

    public List<OutboxEvent> claimBatch(int batchSize) {
        return claimBatch(batchSize, Instant.now(), Duration.ofMinutes(2));
    }

    /**
     * Claims up to {@code batchSize} due rows. Must run inside one transaction
     * so the row locks and IN_FLIGHT updates are atomic.
     */
    public List<OutboxEvent> claimBatch(int batchSize, Instant now, Duration lease) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }

        Timestamp nowTimestamp = Timestamp.from(now);
        // Recover rows whose dispatcher died before acknowledging or failing.
        jdbc.update(RECLAIM_SQL, nowTimestamp, nowTimestamp);

        List<OutboxEvent> rows = jdbc.query(
                SELECT_CLAIMABLE_SQL, ROW_MAPPER, nowTimestamp, batchSize);
        if (rows.isEmpty()) {
            return List.of();
        }

        Instant leaseUntil = now.plus(lease);
        Timestamp leaseTimestamp = Timestamp.from(leaseUntil);
        List<Object[]> claimArgs = rows.stream()
                .map(event -> new Object[]{leaseTimestamp, event.id()})
                .toList();
        jdbc.batchUpdate(MARK_IN_FLIGHT_SQL, claimArgs);
        return rows.stream().map(event -> event.withClaim(leaseUntil)).toList();
    }

    /**
     * @return true when the event was still IN_FLIGHT and is now PUBLISHED
     */
    public boolean markSent(UUID id, Instant sentAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sentAt, "sentAt");
        return jdbc.update(MARK_SENT_SQL, Timestamp.from(sentAt), id) == 1;
    }

    /**
     * Records a transient failure and schedules the next attempt.
     *
     * @return true when the event was still IN_FLIGHT
     */
    public boolean markFailed(UUID id, String error, Instant nextAttemptAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return jdbc.update(MARK_FAILED_SQL, Timestamp.from(nextAttemptAt), error, id) == 1;
    }

    /**
     * Records a permanent failure or exhausted retries.
     *
     * @return true when the event was still IN_FLIGHT
     */
    public boolean markDead(UUID id, String error) {
        Objects.requireNonNull(id, "id");
        return jdbc.update(MARK_DEAD_SQL, error, id) == 1;
    }

    /**
     * Returns an unprocessed event to PENDING without consuming an attempt.
     * Used when a failed event blocks later events in the same partition.
     */
    public boolean release(UUID id, Instant nextAttemptAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return jdbc.update(RELEASE_SQL, Timestamp.from(nextAttemptAt), id) == 1;
    }

    public Optional<OutboxEvent> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbc.query(SELECT_BY_ID_SQL, ROW_MAPPER, id).stream().findFirst();
    }

    private static OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getLong("event_sequence"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getLong("aggregate_version"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("payload"),
                rs.getString("headers"),
                rs.getString("partition_key"),
                instant(rs, "occurred_at"),
                instant(rs, "recorded_at"),
                instant(rs, "next_attempt_at"),
                OutboxEvent.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "sent_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * PostgreSQL needs an explicit JSON cast for a bound varchar. H2 (used by
     * component tests) accepts the plain parameter into its JSON/text columns.
     */
    private static String detectJsonParameter(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return "CAST(? AS JSON)";
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return "H2".equalsIgnoreCase(product) ? "?" : "CAST(? AS JSON)";
        } catch (SQLException ex) {
            return "CAST(? AS JSON)";
        }
    }
}
