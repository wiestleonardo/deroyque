package com.deroyque.ativacao.intercomm.service;

import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;

public interface ProvedorAtivacaoIntercommService {

    /**
     * Envia o id da solicitação como chave de idempotência. Lança IntegracaoException quando a chamada falha.
     */
    public abstract void ativar(SolicitacaoAtivacao solicitacaoAtivacao);

}
