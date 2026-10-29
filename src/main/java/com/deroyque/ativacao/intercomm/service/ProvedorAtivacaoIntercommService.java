package com.deroyque.ativacao.intercomm.service;

import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO;

public interface ProvedorAtivacaoIntercommService {

    /**
     * Envia o id da solicitação como chave de idempotência e nunca lança exceção por falha remota:
     * devolve o resultado já classificado em sucesso, falha definitiva ou falha temporária.
     */
    public abstract ResultadoAtivacaoVO ativar(SolicitacaoAtivacao solicitacaoAtivacao);

}
