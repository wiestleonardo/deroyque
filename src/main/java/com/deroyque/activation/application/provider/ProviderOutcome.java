package com.deroyque.activation.application.provider;

import java.time.Duration;

/** Resultado de uma chamada ao provedor, já classificado pela regra de negócio de nova tentativa. */
public sealed interface ProviderOutcome {

    /** Produto ativado. */
    record Success() implements ProviderOutcome {
    }

    /** O provedor recusou a solicitação; tentar de novo dará o mesmo resultado. */
    record PermanentFailure(String reason) implements ProviderOutcome {
    }

    /**
     * Falha que pode passar (5xx, timeout, 429/503...).
     *
     * @param retryAfter espera sugerida pelo provedor (header Retry-After) ou {@code null}
     */
    record TransientFailure(String reason, Duration retryAfter) implements ProviderOutcome {

        public TransientFailure(String reason) {
            this(reason, null);
        }
    }
}
