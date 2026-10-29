package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.mapper.SolicitacaoAtivacaoMapper;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.util.StringUtil;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolicitacaoAtivacaoServiceImpl implements SolicitacaoAtivacaoService {

    private static final String CAMPO_ORDENACAO = "dataCadastro";
    private static final String PROPRIEDADE_ID_EXISTENTE = "existingRequestId";

    private final SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    private final SolicitacaoAtivacaoMapper solicitacaoAtivacaoMapper;

    private final Clock clock;

    @Override
    public ResultadoCriacaoVO criar(String codigoSistemaOrigem, String codigoCorrelacao, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        Optional<SolicitacaoAtivacao> solicitacaoAtivacaoExistente = this.solicitacaoAtivacaoRepository
                .findByCodigoSistemaOrigemAndCodigoExterno(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId());

        if (solicitacaoAtivacaoExistente.isPresent()) {

            return this.resolverExistente(solicitacaoAtivacaoExistente.get(), criarSolicitacaoAtivacaoDTO);
        }

        SolicitacaoAtivacao solicitacaoAtivacao = SolicitacaoAtivacao.criar(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId(),
                criarSolicitacaoAtivacaoDTO.customerId(), criarSolicitacaoAtivacaoDTO.productCode(), codigoCorrelacao, this.clock.instant());

        try {

            SolicitacaoAtivacao solicitacaoAtivacaoGravada = this.solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao);

            log.info("Solicitação {} criada para o externalId {}", solicitacaoAtivacaoGravada.getIdSolicitacaoAtivacao(),
                    solicitacaoAtivacaoGravada.getCodigoExterno());

            return new ResultadoCriacaoVO(this.solicitacaoAtivacaoMapper.map(solicitacaoAtivacaoGravada), Boolean.TRUE);

        } catch (DataIntegrityViolationException dataIntegrityViolationException) {

            // Duas requisições passaram juntas pela consulta acima; o índice único decidiu quem grava.
            SolicitacaoAtivacao solicitacaoAtivacaoVencedora = this.solicitacaoAtivacaoRepository
                    .findByCodigoSistemaOrigemAndCodigoExterno(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId())
                    .orElseThrow(() -> dataIntegrityViolationException);

            log.info("Criação concorrente do externalId {} resolvida pelo índice único", criarSolicitacaoAtivacaoDTO.externalId());

            return this.resolverExistente(solicitacaoAtivacaoVencedora, criarSolicitacaoAtivacaoDTO);
        }
    }

    @Override
    public SolicitacaoAtivacaoDTO adquirir(String codigoSistemaOrigem, UUID idSolicitacaoAtivacao) {

        SolicitacaoAtivacao solicitacaoAtivacao = this.solicitacaoAtivacaoRepository
                .findByIdSolicitacaoAtivacaoAndCodigoSistemaOrigem(idSolicitacaoAtivacao, codigoSistemaOrigem)
                .orElseThrow(() -> new BusinessServiceException(ErroEnum.SOLICITACAO_NAO_ENCONTRADA));

        return this.solicitacaoAtivacaoMapper.map(solicitacaoAtivacao);
    }

    @Override
    public PaginaDTO<SolicitacaoAtivacaoDTO> listar(String codigoSistemaOrigem, String codigoSituacao, String codigoCliente, Integer pagina,
            Integer tamanhoPagina) {

        SituacaoSolicitacaoAtivacaoEnum situacao = StringUtil.notEmpty(codigoSituacao) ? SituacaoSolicitacaoAtivacaoEnum.getByCodigo(codigoSituacao) : null;
        String codigoClienteFiltro = StringUtil.notEmpty(codigoCliente) ? codigoCliente : null;

        Page<SolicitacaoAtivacao> paginaSolicitacaoAtivacao = this.solicitacaoAtivacaoRepository.listarPorFiltro(codigoSistemaOrigem, situacao,
                codigoClienteFiltro, PageRequest.of(pagina, tamanhoPagina, Sort.by(Sort.Direction.DESC, CAMPO_ORDENACAO)));

        return new PaginaDTO<>(paginaSolicitacaoAtivacao.getContent().stream().map(this.solicitacaoAtivacaoMapper::map).toList(),
                paginaSolicitacaoAtivacao.getNumber(), paginaSolicitacaoAtivacao.getSize(), paginaSolicitacaoAtivacao.getTotalElements(),
                paginaSolicitacaoAtivacao.getTotalPages());
    }

    private ResultadoCriacaoVO resolverExistente(SolicitacaoAtivacao solicitacaoAtivacao, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        if (solicitacaoAtivacao.possuiMesmoConteudo(criarSolicitacaoAtivacaoDTO.customerId(), criarSolicitacaoAtivacaoDTO.productCode())) {

            return new ResultadoCriacaoVO(this.solicitacaoAtivacaoMapper.map(solicitacaoAtivacao), Boolean.FALSE);
        }

        throw new BusinessServiceException(ErroEnum.SOLICITACAO_DUPLICADA, Map.of(PROPRIEDADE_ID_EXISTENTE, solicitacaoAtivacao.getIdSolicitacaoAtivacao()),
                criarSolicitacaoAtivacaoDTO.externalId());
    }

}
