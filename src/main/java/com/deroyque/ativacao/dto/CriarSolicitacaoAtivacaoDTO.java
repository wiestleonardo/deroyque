package com.deroyque.ativacao.dto;

import com.deroyque.ativacao.constants.ValidacaoConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CriarSolicitacaoAtivacaoDTO(

        @NotBlank(message = "{validacao.obrigatorio}")
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR, message = "{validacao.tamanho-maximo}")
        @Pattern(regexp = ValidacaoConstants.REGEX_IDENTIFICADOR, message = "{validacao.formato-identificador}")
        String externalId,

        @NotBlank(message = "{validacao.obrigatorio}")
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR, message = "{validacao.tamanho-maximo}")
        @Pattern(regexp = ValidacaoConstants.REGEX_IDENTIFICADOR, message = "{validacao.formato-identificador}")
        String customerId,

        @NotBlank(message = "{validacao.obrigatorio}")
        @Size(max = ValidacaoConstants.TAMANHO_MAXIMO_IDENTIFICADOR, message = "{validacao.tamanho-maximo}")
        @Pattern(regexp = ValidacaoConstants.REGEX_IDENTIFICADOR, message = "{validacao.formato-identificador}")
        String productCode) {

}
