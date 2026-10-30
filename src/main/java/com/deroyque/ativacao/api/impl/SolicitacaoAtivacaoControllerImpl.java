package com.deroyque.ativacao.api.impl;

import com.deroyque.ativacao.api.SolicitacaoAtivacaoController;
import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SolicitacaoAtivacaoControllerImpl implements SolicitacaoAtivacaoController {

    private final SolicitacaoAtivacaoService solicitacaoAtivacaoService;

    @Override
    public ResponseEntity<SolicitacaoAtivacaoDTO> criar(CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        log.info("Criando a solicitação do externalId {}", criarSolicitacaoAtivacaoDTO.externalId());

        ResultadoCriacaoVO resultadoCriacaoVO = solicitacaoAtivacaoService.criar(criarSolicitacaoAtivacaoDTO);

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
    public ResponseEntity<SolicitacaoAtivacaoDTO> adquirir(UUID id) {

        return ResponseEntity.ok(solicitacaoAtivacaoService.adquirir(id));
    }

    @Override
    public ResponseEntity<PaginaDTO<SolicitacaoAtivacaoDTO>> listar(SituacaoSolicitacaoAtivacaoEnum status, String customerId, Integer page,
            Integer size) {

        return ResponseEntity.ok(solicitacaoAtivacaoService.listar(status, customerId, page, size));
    }

}
