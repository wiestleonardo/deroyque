package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;

/**
 * @param created {@code true} se a solicitação foi criada agora; {@code false} se é um retry
 *                idempotente de uma solicitação que já existia com o mesmo conteúdo.
 */
public record CreationResult(ActivationRequest request, boolean created) {
}
