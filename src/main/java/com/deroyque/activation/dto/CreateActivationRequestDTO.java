package com.deroyque.activation.dto;

import com.deroyque.activation.constants.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateActivationRequestDTO(

        @NotBlank(message = "{validation.required}")
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH, message = "{validation.max-length}")
        @Pattern(regexp = ValidationConstants.IDENTIFIER_REGEX, message = "{validation.identifier-format}")
        String externalId,

        @NotBlank(message = "{validation.required}")
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH, message = "{validation.max-length}")
        @Pattern(regexp = ValidationConstants.IDENTIFIER_REGEX, message = "{validation.identifier-format}")
        String customerId,

        @NotBlank(message = "{validation.required}")
        @Size(max = ValidationConstants.MAX_IDENTIFIER_LENGTH, message = "{validation.max-length}")
        @Pattern(regexp = ValidationConstants.IDENTIFIER_REGEX, message = "{validation.identifier-format}")
        String productCode) {

}
