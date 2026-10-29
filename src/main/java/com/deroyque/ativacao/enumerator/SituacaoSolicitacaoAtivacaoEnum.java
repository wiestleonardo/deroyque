package com.deroyque.ativacao.enumerator;

import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.util.ObjectUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum SituacaoSolicitacaoAtivacaoEnum {

    PENDENTE("PENDING"),
    PROCESSANDO("PROCESSING"),
    CONCLUIDA("COMPLETED"),
    FALHA("FAILED");

    @JsonValue
    private final String codigo;

    @JsonCreator
    public static SituacaoSolicitacaoAtivacaoEnum getByCodigo(String codigo) {

        return Arrays.stream(values())
                .filter(situacaoSolicitacaoAtivacaoEnum -> ObjectUtil.isEquals(situacaoSolicitacaoAtivacaoEnum.getCodigo(), codigo))
                .findFirst()
                .orElseThrow(() -> new BusinessServiceException(ErroEnum.SITUACAO_INVALIDA));
    }

    public Boolean podeTransicionarPara(SituacaoSolicitacaoAtivacaoEnum situacaoDestino) {

        return switch (this) {
            case PENDENTE -> situacaoDestino == PROCESSANDO;
            case PROCESSANDO -> situacaoDestino == CONCLUIDA || situacaoDestino == FALHA || situacaoDestino == PENDENTE;
            case CONCLUIDA, FALHA -> false;
        };
    }

    public Boolean isFinal() {

        return this == CONCLUIDA || this == FALHA;
    }

}
