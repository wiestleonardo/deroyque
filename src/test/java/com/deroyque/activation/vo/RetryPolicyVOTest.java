package com.deroyque.activation.vo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyVOTest {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final RetryPolicyVO retryPolicyVO = new RetryPolicyVO(INITIAL_BACKOFF, MAX_BACKOFF, new Random(42));

    @Test
    void shouldGrowExponentiallyWithJitterWhenThereIsNoRetryAfter() {

        for (int attempts = 1; attempts <= 6; attempts++) {

            Duration fullDelay = INITIAL_BACKOFF.multipliedBy(1L << (attempts - 1));

            Duration delay = this.retryPolicyVO.calculateDelay(attempts, null);

            assertThat(delay).isBetween(fullDelay.dividedBy(2), fullDelay);
        }
    }

    @Test
    void shouldCapAtMaxBackoffWhenThereAreManyAttempts() {

        assertThat(this.retryPolicyVO.calculateDelay(50, null)).isLessThanOrEqualTo(MAX_BACKOFF);
    }

    @Test
    void shouldUseProviderDelayWhenRetryAfterIsGiven() {

        assertThat(this.retryPolicyVO.calculateDelay(1, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldCapAtMaxBackoffWhenRetryAfterIsLonger() {

        assertThat(this.retryPolicyVO.calculateDelay(1, Duration.ofHours(2))).isEqualTo(MAX_BACKOFF);
    }

}
