package com.deroyque.activation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Regras de validação (documentadas no README):
 * todos obrigatórios, até 64 caracteres, apenas letras, números, '.', '_' e '-'.
 */
public record CreateActivationRequestBody(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = MESSAGE)
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = MESSAGE)
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = MESSAGE)
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";
    static final String MESSAGE = "deve conter apenas letras, números, '.', '_' ou '-'";
}
