package com.eia.platform.libs.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Claims one outbox batch and delivers it over HTTP.
 *
 * <p>Events are grouped by partition key and delivered in claim order so
 * per-aggregate ordering is preserved. If an event fails, later events in the
 * same partition are released back to PENDING and not delivered ahead of it.
 * The database transaction ends before HTTP calls, so a slow subscriber never
 * holds outbox row locks.</p>
 */
public class OutboxDispatchService {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatchService.class);
    private static final int MAX_ERROR_LENGTH = 4000;
    private static final String DEFAULT_ERROR = "dispatch failed";

    private final OutboxRepository repository;
    private final OutboxDispatcher dispatcher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxDispatchService(
            OutboxRepository repository,
            OutboxDispatcher dispatcher,
            OutboxProperties properties,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return number of events acknowledged by the subscriber
     */
    public int dispatchBatch() {
        Instant now = clock.instant();
        List<OutboxEvent> claimed = repository.claimBatch(
                properties.effectiveBatchSize(), now, properties.effectiveClaimLease());
        if (claimed.isEmpty()) {
            return 0;
        }

        Map<String, List<OutboxEvent>> partitions = new LinkedHashMap<>();
        for (OutboxEvent event : claimed) {
            String partition = event.partitionKey() == null || event.partitionKey().isBlank()
                    ? event.id().toString()
                    : event.partitionKey();
            partitions.computeIfAbsent(partition, ignored -> new ArrayList<>()).add(event);
        }

        int delivered = 0;
        for (List<OutboxEvent> partition : partitions.values()) {
            for (int index = 0; index < partition.size(); index++) {
                OutboxEvent event = partition.get(index);
                OutboxDispatchResult result = dispatchSafely(event);
                if (result.outcome() == OutboxDispatchResult.Outcome.DELIVERED) {
                    if (repository.markSent(event.id(), now)) {
                        delivered++;
                    } else {
                        LOG.warn("Outbox event {} was no longer IN_FLIGHT when marking PUBLISHED",
                                event.id());
                    }
                    continue;
                }

                String error = errorMessage(result);
                if (result.outcome() == OutboxDispatchResult.Outcome.DEAD) {
                    markDead(event, error);
                } else {
                    markFailed(event, error, now);
                }

                // Preserve per-aggregate order: do not deliver successors ahead
                // of the failed event.
                for (int remaining = index + 1; remaining < partition.size(); remaining++) {
                    OutboxEvent blocked = partition.get(remaining);
                    if (!repository.release(blocked.id(), now)) {
                        LOG.warn("Outbox event {} was no longer IN_FLIGHT when releasing", blocked.id());
                    }
                }
                break;
            }
        }
        return delivered;
    }

    private OutboxDispatchResult dispatchSafely(OutboxEvent event) {
        try {
            OutboxDispatchResult result = dispatcher.dispatch(event.toEnvelope());
            return result == null
                    ? OutboxDispatchResult.retry("Dispatcher returned no result")
                    : result;
        } catch (RuntimeException ex) {
            LOG.error("Unexpected outbox dispatcher failure for event {}", event.id(), ex);
            return OutboxDispatchResult.retry("Dispatcher failure: " + ex.getMessage());
        }
    }

    private void markFailed(OutboxEvent event, String error, Instant now) {
        int failedAttempt = event.attempts() + 1;
        if (failedAttempt > properties.effectiveMaxAttempts()) {
            markDead(event, error);
            return;
        }
        Instant nextAttemptAt = now.plus(OutboxBackoff.delayForAttempt(failedAttempt));
        if (repository.markFailed(event.id(), error, nextAttemptAt)) {
            LOG.warn("Outbox event {} failed attempt {}; next attempt at {}: {}",
                    event.id(), failedAttempt, nextAttemptAt, error);
        } else {
            LOG.warn("Outbox event {} was no longer IN_FLIGHT when marking failed", event.id());
        }
    }

    private void markDead(OutboxEvent event, String error) {
        if (repository.markDead(event.id(), error)) {
            LOG.error("Outbox event {} exhausted retries or was permanently rejected: {}",
                    event.id(), error);
        } else {
            LOG.warn("Outbox event {} was no longer IN_FLIGHT when marking DEAD", event.id());
        }
    }

    private static String errorMessage(OutboxDispatchResult result) {
        String error = result.error();
        if (error == null || error.isBlank()) {
            error = DEFAULT_ERROR;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
