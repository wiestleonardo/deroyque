package com.deroyque.activation.config;

import com.deroyque.activation.constants.FailureReasonConstants;
import com.deroyque.activation.exception.IntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Turns any failure of an outgoing HTTP call into an {@link IntegrationException}. Any non-2xx response is a failure:
 * 5xx, 408, 429 and 401/403 (our credential) are retryable, the other statuses are permanent. Timeouts and network
 * errors are retryable.
 */
@Slf4j
@Component
public class IntegrationErrorHandler {

    public RestClient.Builder apply(RestClient.Builder restClientBuilder) {

        return restClientBuilder
                .defaultStatusHandler(httpStatusCode -> !httpStatusCode.is2xxSuccessful(), (httpRequest, clientHttpResponse) -> {

                    throw this.fromResponse(clientHttpResponse);
                })
                .requestInterceptor((httpRequest, body, execution) -> {

                    try {

                        ClientHttpResponse clientHttpResponse = execution.execute(httpRequest, body);
                        // Reading the status here makes a read timeout surface inside this try.
                        clientHttpResponse.getStatusCode();

                        return clientHttpResponse;

                    } catch (SocketTimeoutException socketTimeoutException) {

                        throw new IntegrationException(FailureReasonConstants.TIMEOUT, true, null);

                    } catch (IOException ioException) {

                        throw new IntegrationException(FailureReasonConstants.UNREACHABLE, true, null);
                    }
                });
    }

    private IntegrationException fromResponse(ClientHttpResponse clientHttpResponse) throws IOException {

        HttpStatusCode httpStatusCode = clientHttpResponse.getStatusCode();
        int statusCode = httpStatusCode.value();

        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value() || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()) {

            return new IntegrationException(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, true,
                    this.parseRetryAfter(clientHttpResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        }

        if (statusCode == HttpStatus.UNAUTHORIZED.value() || statusCode == HttpStatus.FORBIDDEN.value()) {

            // The request is fine, our credential is not: retry so requests recover once the secret is fixed.
            log.error("Provider rejected our credential with status {}; check the API key", statusCode);

            return new IntegrationException(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, true, null);
        }

        if (statusCode == HttpStatus.REQUEST_TIMEOUT.value() || httpStatusCode.is5xxServerError()) {

            return new IntegrationException(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, true, null);
        }

        return new IntegrationException(FailureReasonConstants.REJECTED_PREFIX + statusCode, false, null);
    }

    private Duration parseRetryAfter(String retryAfter) {

        try {

            return retryAfter == null ? null : Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.trim())));

        } catch (NumberFormatException numberFormatException) {

            return null;
        }
    }

}
