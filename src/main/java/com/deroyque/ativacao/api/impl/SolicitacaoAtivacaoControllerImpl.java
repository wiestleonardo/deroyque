package com.deroyque.ativacao.api.impl;

import com.deroyque.ativacao.api.SolicitacaoAtivacaoController;
import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import com.deroyque.ativacao.util.StringUtil;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SolicitacaoAtivacaoControllerImpl implements SolicitacaoAtivacaoController {

    private static final List<String> LISTA_CLAIM_SISTEMA_ORIGEM = List.of("client_id", "azp");

    private final SolicitacaoAtivacaoService solicitacaoAtivacaoService;

    @Override
    public ResponseEntity<SolicitacaoAtivacaoDTO> criar(Jwt jwt, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        String codigoSistemaOrigem = this.adquirirSistemaOrigem(jwt);

        log.info("Criando a solicitação do externalId {} para o sistema {}", criarSolicitacaoAtivacaoDTO.externalId(), codigoSistemaOrigem);

        ResultadoCriacaoVO resultadoCriacaoVO = this.solicitacaoAtivacaoService.criar(codigoSistemaOrigem, CorrelacaoUtil.adquirirAtual(),
                criarSolicitacaoAtivacaoDTO);

        if (!resultadoCriacaoVO.criada()) {

            return ResponseEntity.ok(resultadoCriacaoVO.solicitacaoAtivacaoDTO());
        }

        URI localizacao = ServletUriComponentsBuilder.fromCurrentRequest()
                .path(RotaConstants.SolicitacaoAtivacao.ADQUIRIR)
                .buildAndExpand(resultadoCriacaoVO.solicitacaoAtivacaoDTO().id())
                .toUri();

        return ResponseEntity.created(localizacao).body(resultadoCriacaoVO.solicitacaoAtivacaoDTO());
    }

    @Override
    public ResponseEntity<SolicitacaoAtivacaoDTO> adquirir(Jwt jwt, UUID idSolicitacaoAtivacao) {

        return ResponseEntity.ok(this.solicitacaoAtivacaoService.adquirir(this.adquirirSistemaOrigem(jwt), idSolicitacaoAtivacao));
    }

    @Override
    public ResponseEntity<PaginaDTO<SolicitacaoAtivacaoDTO>> listar(Jwt jwt, String status, String customerId, Integer page, Integer size) {

        return ResponseEntity.ok(this.solicitacaoAtivacaoService.listar(this.adquirirSistemaOrigem(jwt), status, customerId, page, size));
    }

    private String adquirirSistemaOrigem(Jwt jwt) {

        return LISTA_CLAIM_SISTEMA_ORIGEM.stream()
                .map(jwt::getClaimAsString)
                .filter(StringUtil::notEmpty)
                .findFirst()
                .orElse(jwt.getSubject());
    }

}
