package com.deroyque.ativacao.dto;

import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SolicitacaoAtivacaoDTO(

        UUID id,

        String externalId,

        String customerId,

        String productCode,

        SituacaoSolicitacaoAtivacaoEnum status,

        Integer attempts,

        String failureReason,

        Instant createdAt,

        Instant updatedAt) {

}
