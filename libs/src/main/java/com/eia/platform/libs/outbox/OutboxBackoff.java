package com.eia.platform.libs.outbox;

import java.time.Duration;

/**
 * Exponential-style retry schedule from SPEC 7.5.1:
 * 1s, 5s, 30s, 2m, 10m, 30m, 1h, 2h, 6h, 12h, 24h, 24h.
 */
public final class OutboxBackoff {
    private static final Duration[] DELAYS = {
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(2),
            Duration.ofHours(6),
            Duration.ofHours(12),
            Duration.ofHours(24),
            Duration.ofHours(24)
    };

    private OutboxBackoff() {
    }

    /**
     * @param failedAttempt 1 for the first failed delivery attempt
     */
    public static Duration delayForAttempt(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be at least 1");
        }
        return DELAYS[Math.min(failedAttempt, DELAYS.length) - 1];
    }
}
