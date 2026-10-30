package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.MotivoFalhaConstants;
import com.deroyque.ativacao.exception.IntegracaoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Transforma qualquer falha de chamada HTTP de saída em {@link IntegracaoException}. Toda resposta fora de 2xx é falha:
 * 5xx, 408, 429 e 401/403 (nossa credencial) permitem nova tentativa; os demais status são definitivos. Timeout e erro de
 * rede permitem nova tentativa.
 */
@Slf4j
@Component
public class IntegracaoErrorHandler {

    public RestClient.Builder aplicar(RestClient.Builder restClientBuilder) {

        return restClientBuilder
                .defaultStatusHandler(httpStatusCode -> !httpStatusCode.is2xxSuccessful(), (httpRequest, clientHttpResponse) -> {

                    throw this.converterResposta(clientHttpResponse);
                })
                .requestInterceptor((httpRequest, body, execution) -> {

                    try {

                        ClientHttpResponse clientHttpResponse = execution.execute(httpRequest, body);
                        // Ler o status aqui faz o timeout de leitura acontecer dentro deste try.
                        clientHttpResponse.getStatusCode();

                        return clientHttpResponse;

                    } catch (HttpTimeoutException | SocketTimeoutException timeoutException) {

                        throw new IntegracaoException(MotivoFalhaConstants.TEMPO_LIMITE, true, null);

                    } catch (IOException ioException) {

                        throw new IntegracaoException(MotivoFalhaConstants.INACESSIVEL, true, null);
                    }
                });
    }

    private IntegracaoException converterResposta(ClientHttpResponse clientHttpResponse) throws IOException {

        HttpStatusCode httpStatusCode = clientHttpResponse.getStatusCode();
        int codigoStatus = httpStatusCode.value();

        if (codigoStatus == HttpStatus.TOO_MANY_REQUESTS.value() || codigoStatus == HttpStatus.SERVICE_UNAVAILABLE.value()) {

            return new IntegracaoException(MotivoFalhaConstants.PREFIXO_ERRO_HTTP + codigoStatus, true,
                    this.converterRetryAfter(clientHttpResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        }

        if (codigoStatus == HttpStatus.UNAUTHORIZED.value() || codigoStatus == HttpStatus.FORBIDDEN.value()) {

            // A solicitação está certa, a nossa credencial não: nova tentativa para recuperar quando o segredo for corrigido.
            log.error("Provedor recusou a credencial do serviço com status {}; conferir a chave de API", codigoStatus);

            return new IntegracaoException(MotivoFalhaConstants.PREFIXO_ERRO_HTTP + codigoStatus, true, null);
        }

        if (codigoStatus == HttpStatus.REQUEST_TIMEOUT.value() || httpStatusCode.is5xxServerError()) {

            return new IntegracaoException(MotivoFalhaConstants.PREFIXO_ERRO_HTTP + codigoStatus, true, null);
        }

        return new IntegracaoException(MotivoFalhaConstants.PREFIXO_RECUSA + codigoStatus, false, null);
    }

    private Duration converterRetryAfter(String retryAfter) {

        try {

            return retryAfter == null ? null : Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.trim())));

        } catch (NumberFormatException numberFormatException) {

            return null;
        }
    }

}
