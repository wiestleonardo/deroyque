package com.deroyque.activation.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), new Random(42));

    @Test
    void delayGrowsExponentiallyWithJitterBetweenHalfAndFull() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            var full = Duration.ofSeconds(2).multipliedBy(1L << (attempt - 1));
            var delay = policy.delayFor(attempt, null);

            assertThat(delay).isBetween(full.dividedBy(2), full);
        }
    }

    @Test
    void delayIsCappedAtMaxBackoff() {
        assertThat(policy.delayFor(50, null)).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void retryAfterFromProviderWins() {
        assertThat(policy.delayFor(1, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterIsCappedAtMaxBackoff() {
        assertThat(policy.delayFor(1, Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(5));
    }
}
