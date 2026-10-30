package com.deroyque.activation.dto;

import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationRequestDTO(

        UUID id,

        String externalId,

        String customerId,

        String productCode,

        ActivationStatusEnum status,

        Integer attempts,

        String failureReason,

        Instant createdAt,

        Instant updatedAt) {

}
