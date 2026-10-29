package com.deroyque.ativacao.intercomm.service.impl;

import com.deroyque.ativacao.constants.MotivoFalhaConstants;
import com.deroyque.ativacao.dto.ApiAtivacaoProvedorDTO;
import com.deroyque.ativacao.intercomm.client.ProvedorAtivacaoClient;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import com.deroyque.ativacao.util.LongUtil;
import com.deroyque.ativacao.util.ObjectUtil;
import com.deroyque.ativacao.util.StringUtil;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaDefinitiva;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaTemporaria;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.Sucesso;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProvedorAtivacaoIntercommServiceImpl implements ProvedorAtivacaoIntercommService {

    private static final Set<HttpStatus> STATUS_COM_ESPERA = Set.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE);
    private static final Set<HttpStatus> STATUS_CREDENCIAL_RECUSADA = Set.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

    private final ProvedorAtivacaoClient provedorAtivacaoClient;

    private final Clock clock;

    @Override
    public ResultadoAtivacaoVO ativar(SolicitacaoAtivacao solicitacaoAtivacao) {

        ApiAtivacaoProvedorDTO apiAtivacaoProvedorDTO = new ApiAtivacaoProvedorDTO(solicitacaoAtivacao.getIdSolicitacaoAtivacao(),
                solicitacaoAtivacao.getCodigoCliente(), solicitacaoAtivacao.getCodigoProduto());

        try {

            ResponseEntity<Void> responseEntity = this.provedorAtivacaoClient.ativar(solicitacaoAtivacao.getIdSolicitacaoAtivacao().toString(),
                    CorrelacaoUtil.adquirirAtual(), apiAtivacaoProvedorDTO);

            return this.classificar(responseEntity.getStatusCode(), responseEntity.getHeaders());

        } catch (RestClientResponseException restClientResponseException) {

            return this.classificar(restClientResponseException.getStatusCode(), restClientResponseException.getResponseHeaders());

        } catch (ResourceAccessException resourceAccessException) {

            // Timeout é ambíguo: o provedor pode ter ativado. A nova tentativa reenvia a mesma chave de idempotência.
            String motivoFalha = this.isTempoLimite(resourceAccessException) ? MotivoFalhaConstants.TEMPO_LIMITE_PROVEDOR
                    : MotivoFalhaConstants.PROVEDOR_INACESSIVEL;

            log.warn("Falha de comunicação com o provedor na solicitação {}: {} ({})", solicitacaoAtivacao.getIdSolicitacaoAtivacao(), motivoFalha,
                    resourceAccessException.getMessage());

            return new FalhaTemporaria(motivoFalha);
        }
    }

    private ResultadoAtivacaoVO classificar(HttpStatusCode httpStatusCode, HttpHeaders httpHeaders) {

        if (httpStatusCode.is2xxSuccessful()) {

            return new Sucesso();
        }

        HttpStatus httpStatus = HttpStatus.resolve(httpStatusCode.value());
        Integer codigoStatus = httpStatusCode.value();

        if (STATUS_COM_ESPERA.contains(httpStatus)) {

            String retryAfter = ObjectUtil.isNull(httpHeaders) ? null : httpHeaders.getFirst(HttpHeaders.RETRY_AFTER);

            return new FalhaTemporaria(MotivoFalhaConstants.PREFIXO_ERRO_HTTP + codigoStatus, this.converterRetryAfter(retryAfter));
        }

        if (STATUS_CREDENCIAL_RECUSADA.contains(httpStatus)) {

            log.error("Provedor recusou a credencial do serviço com status {}; conferir o segredo", codigoStatus);

            return new FalhaTemporaria(MotivoFalhaConstants.PREFIXO_CREDENCIAL + codigoStatus);
        }

        if (httpStatus == HttpStatus.REQUEST_TIMEOUT || httpStatusCode.is5xxServerError()) {

            return new FalhaTemporaria(MotivoFalhaConstants.PREFIXO_ERRO_HTTP + codigoStatus);
        }

        return new FalhaDefinitiva(MotivoFalhaConstants.PREFIXO_RECUSA + codigoStatus);
    }

    private Duration converterRetryAfter(String retryAfter) {

        if (StringUtil.isNullOrEmpty(retryAfter)) {

            return null;
        }

        Long segundos = LongUtil.toLong(retryAfter);

        if (ObjectUtil.isNotNull(segundos)) {

            return segundos < 0 ? Duration.ZERO : Duration.ofSeconds(segundos);
        }

        try {

            Instant dataRetryAfter = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration espera = Duration.between(this.clock.instant(), dataRetryAfter);

            return espera.isNegative() ? Duration.ZERO : espera;

        } catch (DateTimeParseException dateTimeParseException) {

            return null;
        }
    }

    private Boolean isTempoLimite(Throwable throwable) {

        for (Throwable causa = throwable; ObjectUtil.isNotNull(causa); causa = causa.getCause()) {

            if (causa instanceof HttpTimeoutException || causa instanceof SocketTimeoutException) {

                return true;
            }
        }

        return false;
    }

}
