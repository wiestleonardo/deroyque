package com.deroyque.ativacao.enumerator;

public enum SituacaoSolicitacaoAtivacaoEnum {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public Boolean podeMudarPara(SituacaoSolicitacaoAtivacaoEnum situacaoDestino) {

        return switch (this) {
            case PENDING -> situacaoDestino == PROCESSING;
            case PROCESSING -> situacaoDestino == COMPLETED || situacaoDestino == FAILED || situacaoDestino == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

}
