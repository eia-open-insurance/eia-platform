package com.eia.platform.libs.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxBackoffTest {
    @Test
    void followsTheSpecRetrySchedule() {
        assertThat(OutboxBackoff.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxBackoff.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(5));
        assertThat(OutboxBackoff.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxBackoff.delayForAttempt(4)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OutboxBackoff.delayForAttempt(5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(OutboxBackoff.delayForAttempt(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(OutboxBackoff.delayForAttempt(7)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxBackoff.delayForAttempt(8)).isEqualTo(Duration.ofHours(2));
        assertThat(OutboxBackoff.delayForAttempt(9)).isEqualTo(Duration.ofHours(6));
        assertThat(OutboxBackoff.delayForAttempt(10)).isEqualTo(Duration.ofHours(12));
        assertThat(OutboxBackoff.delayForAttempt(11)).isEqualTo(Duration.ofHours(24));
        assertThat(OutboxBackoff.delayForAttempt(12)).isEqualTo(Duration.ofHours(24));
        assertThat(OutboxBackoff.delayForAttempt(13)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectsZeroOrNegativeAttempts() {
        assertThatThrownBy(() -> OutboxBackoff.delayForAttempt(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxBackoff.delayForAttempt(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
