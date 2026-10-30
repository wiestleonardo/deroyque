package com.deroyque.ativacao.dto;

import java.util.UUID;

public record ApiAtivacaoProvedorDTO(

        UUID requestId,

        String customerId,

        String productCode) {

}
