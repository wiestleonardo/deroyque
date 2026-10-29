package com.deroyque.ativacao.intercomm.client;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.dto.ApiAtivacaoProvedorDTO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
public interface ProvedorAtivacaoClient {

    @PostExchange(RotaConstants.ProvedorAtivacao.ATIVAR)
    public abstract ResponseEntity<Void> ativar(@RequestHeader(HeaderConstants.CHAVE_IDEMPOTENCIA) String chaveIdempotencia,
            @RequestHeader(name = HeaderConstants.CORRELACAO, required = false) String codigoCorrelacao,
            @RequestBody ApiAtivacaoProvedorDTO apiAtivacaoProvedorDTO);

}
