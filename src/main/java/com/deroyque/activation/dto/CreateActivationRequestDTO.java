package com.deroyque.activation.dto;

import com.deroyque.activation.constants.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequestDTO(

        @NotBlank
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH)
        String externalId,

        @NotBlank
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH)
        String customerId,

        @NotBlank
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH)
        String productCode) {

}
