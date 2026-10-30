package com.deroyque.activation.config;

import com.deroyque.activation.constants.FailureReasonConstants;
import com.deroyque.activation.exception.IntegrationException;
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
 * Turns any failure of an outgoing HTTP call into an {@link IntegrationException}: 4xx is permanent,
 * 5xx, 408 and 429 are retryable (honoring Retry-After in seconds), and timeouts or network errors are retryable.
 */
@Component
public class IntegrationErrorHandler {

    public RestClient.Builder apply(RestClient.Builder restClientBuilder) {

        return restClientBuilder
                .defaultStatusHandler(HttpStatusCode::isError, (httpRequest, clientHttpResponse) -> {

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

        int statusCode = clientHttpResponse.getStatusCode().value();

        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value() || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()) {

            return new IntegrationException(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, true,
                    this.parseRetryAfter(clientHttpResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        }

        if (statusCode == HttpStatus.REQUEST_TIMEOUT.value() || clientHttpResponse.getStatusCode().is5xxServerError()) {

            return new IntegrationException(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, true, null);
        }

        return new IntegrationException(FailureReasonConstants.REJECTED_PREFIX + statusCode, false, null);
    }

    private Duration parseRetryAfter(String retryAfter) {

        try {

            return retryAfter == null ? null : Duration.ofSeconds(Long.parseLong(retryAfter.trim()));

        } catch (NumberFormatException numberFormatException) {

            return null;
        }
    }

}
