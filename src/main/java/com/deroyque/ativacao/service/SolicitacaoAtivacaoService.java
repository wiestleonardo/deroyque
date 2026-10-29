package com.deroyque.ativacao.service;

import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;

import java.util.UUID;

public interface SolicitacaoAtivacaoService {

    /**
     * Mesmo externalId com mesmo conteúdo devolve o registro existente com {@code criada = false};
     * com conteúdo diferente lança SOLICITACAO_DUPLICADA.
     */
    public abstract ResultadoCriacaoVO criar(String codigoSistemaOrigem, String codigoCorrelacao, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO);

    public abstract SolicitacaoAtivacaoDTO adquirir(String codigoSistemaOrigem, UUID idSolicitacaoAtivacao);

    public abstract PaginaDTO<SolicitacaoAtivacaoDTO> listar(String codigoSistemaOrigem, String codigoSituacao, String codigoCliente, Integer pagina,
            Integer tamanhoPagina);

}
