package com.deroyque.activation.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Backoff exponencial com jitter ("equal jitter"): metade fixa + metade aleatória, para que muitas
 * solicitações que falharam juntas não voltem ao provedor todas no mesmo instante.
 */
public class RetryPolicy {

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final RandomGenerator random;

    public RetryPolicy(Duration initialBackoff, Duration maxBackoff, RandomGenerator random) {
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.random = random;
    }

    /**
     * @param attempt    tentativas já feitas (1 = acabou de falhar a primeira)
     * @param retryAfter espera pedida pelo provedor; quando existe, é respeitada (limitada ao teto)
     */
    public Duration delayFor(int attempt, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            return min(retryAfter, maxBackoff);
        }
        var exponent = Math.min(Math.max(attempt - 1, 0), 30);
        var exponential = min(initialBackoff.multipliedBy(1L << exponent), maxBackoff);
        var half = exponential.toMillis() / 2;
        var jitter = half == 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
