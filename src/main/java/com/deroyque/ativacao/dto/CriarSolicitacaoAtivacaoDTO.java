package com.deroyque.ativacao.dto;

import com.deroyque.ativacao.constants.ValidacaoConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CriarSolicitacaoAtivacaoDTO(

        @NotBlank
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR)
        String externalId,

        @NotBlank
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR)
        String customerId,

        @NotBlank
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR)
        String productCode) {

}
