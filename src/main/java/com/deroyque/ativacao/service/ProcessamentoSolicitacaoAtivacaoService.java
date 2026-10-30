package com.deroyque.ativacao.service;

import java.util.UUID;

public interface ProcessamentoSolicitacaoAtivacaoService {

    public abstract void processarPendentes();

    public abstract void processar(UUID idSolicitacaoAtivacao);

}
