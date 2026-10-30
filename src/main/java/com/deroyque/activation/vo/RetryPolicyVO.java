package com.deroyque.activation.vo;

import com.deroyque.activation.util.ObjectUtil;

import java.time.Duration;
import java.util.random.RandomGenerator;

public record RetryPolicyVO(Duration initialBackoff, Duration maxBackoff, RandomGenerator randomGenerator) {

    private static final Integer MAX_EXPONENT = 30;

    public Duration calculateDelay(Integer attempts, Duration retryAfter) {

        if (ObjectUtil.isNotNull(retryAfter) && !retryAfter.isNegative()) {

            return min(retryAfter, this.maxBackoff);
        }

        int exponent = Math.clamp(attempts - 1L, 0, MAX_EXPONENT);
        Duration exponentialDelay = min(this.initialBackoff.multipliedBy(1L << exponent), this.maxBackoff);

        long halfDelay = exponentialDelay.toMillis() / 2;
        long jitter = halfDelay == 0 ? 0 : this.randomGenerator.nextLong(halfDelay + 1);

        return Duration.ofMillis(halfDelay + jitter);
    }

    private static Duration min(Duration duration, Duration otherDuration) {

        return duration.compareTo(otherDuration) <= 0 ? duration : otherDuration;
    }

}
