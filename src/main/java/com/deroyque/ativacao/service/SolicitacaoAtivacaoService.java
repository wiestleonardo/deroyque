package com.deroyque.ativacao.service;

import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;

import java.util.UUID;

public interface SolicitacaoAtivacaoService {

    /**
     * Mesmo externalId com o mesmo conteúdo devolve o registro existente com {@code criada = false};
     * com conteúdo diferente lança SOLICITACAO_DUPLICADA.
     */
    public abstract ResultadoCriacaoVO criar(CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO);

    public abstract SolicitacaoAtivacaoDTO adquirir(UUID idSolicitacaoAtivacao);

    public abstract PaginaDTO<SolicitacaoAtivacaoDTO> listar(SituacaoSolicitacaoAtivacaoEnum situacao, String codigoCliente, Integer pagina,
            Integer tamanhoPagina);

}
