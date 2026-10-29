package com.deroyque.activation.application;

/**
 * @param clientId sistema consumidor autenticado (dono da solicitação)
 */
public record CreateActivationCommand(
        String clientId,
        String externalId,
        String customerId,
        String productCode,
        String correlationId) {
}
