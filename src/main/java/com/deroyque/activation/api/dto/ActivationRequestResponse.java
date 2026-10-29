package com.deroyque.activation.api.dto;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública da solicitação. Não expõe dados internos (versão, lease, clientId).
 * {@code failureReason} é um código (ex.: PROVIDER_REJECTED_400), nunca a mensagem crua do provedor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationRequestResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(
                request.getId(),
                request.getExternalId(),
                request.getCustomerId(),
                request.getProductCode(),
                request.getStatus(),
                request.getAttempts(),
                request.getFailureReason(),
                request.getCreatedAt(),
                request.getUpdatedAt());
    }
}
