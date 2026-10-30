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
import com.deroyque.ativacao.service.SistemaAutenticadoService;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.util.CorrelacaoUtil;
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

    private static final Sort MAIS_RECENTES_PRIMEIRO = Sort.by(Sort.Direction.DESC, "dataCadastro");

    private final SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    private final SolicitacaoAtivacaoMapper solicitacaoAtivacaoMapper;

    private final SistemaAutenticadoService sistemaAutenticadoService;

    private final Clock clock;

    @Override
    public ResultadoCriacaoVO criar(CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        String codigoSistemaOrigem = sistemaAutenticadoService.adquirirSistemaOrigem();

        Optional<SolicitacaoAtivacao> solicitacaoAtivacaoExistente = solicitacaoAtivacaoRepository
                .findByCodigoSistemaOrigemAndCodigoExterno(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId());

        if (solicitacaoAtivacaoExistente.isPresent()) {

            return this.resolverExistente(solicitacaoAtivacaoExistente.get(), criarSolicitacaoAtivacaoDTO);
        }

        SolicitacaoAtivacao solicitacaoAtivacao = SolicitacaoAtivacao.criar(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId(),
                criarSolicitacaoAtivacaoDTO.customerId(), criarSolicitacaoAtivacaoDTO.productCode(), CorrelacaoUtil.adquirirAtual(), clock.instant());

        try {

            SolicitacaoAtivacao solicitacaoAtivacaoGravada = solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao);

            log.info("Solicitação {} criada para o externalId {}", solicitacaoAtivacaoGravada.getIdSolicitacaoAtivacao(),
                    solicitacaoAtivacaoGravada.getCodigoExterno());

            return new ResultadoCriacaoVO(solicitacaoAtivacaoMapper.map(solicitacaoAtivacaoGravada), true);

        } catch (DataIntegrityViolationException dataIntegrityViolationException) {

            // Duas requisições iguais passaram juntas pela consulta acima; o índice único deixou só uma gravar.
            SolicitacaoAtivacao solicitacaoAtivacaoVencedora = solicitacaoAtivacaoRepository
                    .findByCodigoSistemaOrigemAndCodigoExterno(codigoSistemaOrigem, criarSolicitacaoAtivacaoDTO.externalId())
                    .orElseThrow(() -> dataIntegrityViolationException);

            return this.resolverExistente(solicitacaoAtivacaoVencedora, criarSolicitacaoAtivacaoDTO);
        }
    }

    @Override
    public SolicitacaoAtivacaoDTO adquirir(UUID idSolicitacaoAtivacao) {

        return solicitacaoAtivacaoRepository
                .findByIdSolicitacaoAtivacaoAndCodigoSistemaOrigem(idSolicitacaoAtivacao, sistemaAutenticadoService.adquirirSistemaOrigem())
                .map(solicitacaoAtivacaoMapper::map)
                .orElseThrow(() -> new BusinessServiceException(ErroEnum.SOLICITACAO_NAO_ENCONTRADA));
    }

    @Override
    public PaginaDTO<SolicitacaoAtivacaoDTO> listar(SituacaoSolicitacaoAtivacaoEnum situacao, String codigoCliente, Integer pagina,
            Integer tamanhoPagina) {

        String codigoClienteFiltro = codigoCliente == null || codigoCliente.isBlank() ? null : codigoCliente;

        Page<SolicitacaoAtivacao> paginaSolicitacaoAtivacao = solicitacaoAtivacaoRepository.listarPorFiltro(
                sistemaAutenticadoService.adquirirSistemaOrigem(), situacao, codigoClienteFiltro, PageRequest.of(pagina, tamanhoPagina, MAIS_RECENTES_PRIMEIRO));

        return new PaginaDTO<>(paginaSolicitacaoAtivacao.getContent().stream().map(solicitacaoAtivacaoMapper::map).toList(),
                paginaSolicitacaoAtivacao.getNumber(), paginaSolicitacaoAtivacao.getSize(), paginaSolicitacaoAtivacao.getTotalElements(),
                paginaSolicitacaoAtivacao.getTotalPages());
    }

    private ResultadoCriacaoVO resolverExistente(SolicitacaoAtivacao solicitacaoAtivacao, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        if (solicitacaoAtivacao.possuiMesmoConteudo(criarSolicitacaoAtivacaoDTO.customerId(), criarSolicitacaoAtivacaoDTO.productCode())) {

            return new ResultadoCriacaoVO(solicitacaoAtivacaoMapper.map(solicitacaoAtivacao), false);
        }

        throw new BusinessServiceException(ErroEnum.SOLICITACAO_DUPLICADA, Map.of("existingRequestId", solicitacaoAtivacao.getIdSolicitacaoAtivacao()),
                criarSolicitacaoAtivacaoDTO.externalId());
    }

}
