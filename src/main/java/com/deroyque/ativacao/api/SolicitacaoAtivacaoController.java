package com.deroyque.ativacao.api;

import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.constants.ValidacaoConstants;
import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@RequestMapping(path = RotaConstants.SolicitacaoAtivacao.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public interface SolicitacaoAtivacaoController {

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public abstract ResponseEntity<SolicitacaoAtivacaoDTO> criar(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO);

    @GetMapping(RotaConstants.SolicitacaoAtivacao.ADQUIRIR)
    public abstract ResponseEntity<SolicitacaoAtivacaoDTO> adquirir(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID idSolicitacaoAtivacao);

    @GetMapping
    public abstract ResponseEntity<PaginaDTO<SolicitacaoAtivacaoDTO>> listar(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ValidacaoConstants.TAMANHO_MAXIMO_PAGINA) Integer size);

}
