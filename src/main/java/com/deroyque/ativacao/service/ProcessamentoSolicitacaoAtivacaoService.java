package com.deroyque.ativacao.service;

import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;

public interface ProcessamentoSolicitacaoAtivacaoService {

    public abstract void processarPendentes();

    public abstract void processar(SolicitacaoAtivacao solicitacaoAtivacao);

}
