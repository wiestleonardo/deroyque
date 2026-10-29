package com.deroyque.activation.domain;

import java.util.Set;

/**
 * Ciclo de vida de uma solicitação de ativação.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *    ▲            │
 *    └────────────┤ (falha temporária: volta para a fila com backoff)
 *                 └────────► FAILED
 * </pre>
 */
public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public Set<ActivationStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> Set.of(PROCESSING);
            case PROCESSING -> Set.of(COMPLETED, FAILED, PENDING);
            case COMPLETED, FAILED -> Set.of();
        };
    }

    public boolean canTransitionTo(ActivationStatus target) {
        return allowedTargets().contains(target);
    }

    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
