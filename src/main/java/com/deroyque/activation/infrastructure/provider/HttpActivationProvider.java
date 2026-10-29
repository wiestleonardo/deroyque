package com.deroyque.activation.infrastructure.provider;

import com.deroyque.activation.application.provider.ActivationProvider;
import com.deroyque.activation.application.provider.ProviderOutcome;
import com.deroyque.activation.application.provider.ProviderOutcome.PermanentFailure;
import com.deroyque.activation.application.provider.ProviderOutcome.Success;
import com.deroyque.activation.application.provider.ProviderOutcome.TransientFailure;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Cliente HTTP do provedor. Regras de classificação (detalhadas no README):
 * 2xx → sucesso; 400/404/422 e demais 4xx → falha definitiva; 5xx, 408, timeout, erro de rede → nova
 * tentativa; 429/503 → nova tentativa respeitando Retry-After; 401/403 → nova tentativa (problema na
 * nossa credencial, não na solicitação).
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String API_KEY_HEADER = "X-Api-Key";

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;
    private final Clock clock;

    public HttpActivationProvider(RestClient.Builder builder, ProviderProperties properties, Clock clock) {
        this.clock = clock;
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        builder = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder = builder.defaultHeader(API_KEY_HEADER, properties.apiKey());
        }
        this.restClient = builder.build();
    }

    @Override
    public ProviderOutcome activate(ActivationRequest request) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY, request.getId().toString())
                    .headers(h -> {
                        var correlationId = CorrelationId.current();
                        if (correlationId != null) {
                            h.set(CorrelationId.HEADER, correlationId);
                        }
                    })
                    .body(new ActivatePayload(request.getId(), request.getCustomerId(), request.getProductCode()))
                    .exchange((req, res) -> classify(res.getStatusCode(), res.getHeaders()), true);
        } catch (ResourceAccessException e) {
            // Timeout é ambíguo: o provedor pode ter ativado. A nova tentativa usa a mesma Idempotency-Key.
            var reason = isTimeout(e) ? "PROVIDER_TIMEOUT" : "PROVIDER_UNREACHABLE";
            log.warn("Falha de comunicação com o provedor id={} motivo={} erro={}", request.getId(), reason,
                    e.getMessage());
            return new TransientFailure(reason);
        }
    }

    ProviderOutcome classify(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new Success();
        }
        if (code == 429 || code == 503) {
            return new TransientFailure("PROVIDER_HTTP_" + code, parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (code == 401 || code == 403) {
            log.error("Provedor recusou nossa credencial (HTTP {}); verificar segredo/rotação", code);
            return new TransientFailure("PROVIDER_AUTH_" + code);
        }
        if (code == 408 || status.is5xxServerError()) {
            return new TransientFailure("PROVIDER_HTTP_" + code);
        }
        return new PermanentFailure("PROVIDER_REJECTED_" + code);
    }

    /** Retry-After pode vir em segundos ou como data HTTP (RFC 9110). Valor inválido é ignorado. */
    Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            // tenta formato de data
        }
        try {
            var date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var delay = Duration.between(clock.instant(), date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    record ActivatePayload(UUID requestId, String customerId, String productCode) {
    }
}
