package com.eia.platform.libs.outbox;

import java.util.Objects;

/**
 * Result of one HTTP delivery attempt.
 */
public record OutboxDispatchResult(Outcome outcome, String error) {
    public enum Outcome {
        /**
         * Subscriber acknowledged with a 2xx response.
         */
        DELIVERED,
        /**
         * Transient failure: keep PENDING and schedule the next attempt.
         */
        RETRY,
        /**
         * Permanent consumer rejection (or exhausted attempts): mark DEAD.
         */
        DEAD
    }

    public OutboxDispatchResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static OutboxDispatchResult delivered() {
        return new OutboxDispatchResult(Outcome.DELIVERED, null);
    }

    public static OutboxDispatchResult retry(String error) {
        return new OutboxDispatchResult(Outcome.RETRY, error);
    }

    public static OutboxDispatchResult dead(String error) {
        return new OutboxDispatchResult(Outcome.DEAD, error);
    }

    public boolean isDelivered() {
        return outcome == Outcome.DELIVERED;
    }
}
