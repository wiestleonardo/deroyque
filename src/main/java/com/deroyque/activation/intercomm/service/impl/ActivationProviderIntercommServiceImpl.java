package com.deroyque.activation.intercomm.service.impl;

import com.deroyque.activation.constants.FailureReasonConstants;
import com.deroyque.activation.dto.ApiProviderActivationDTO;
import com.deroyque.activation.intercomm.client.ActivationProviderClient;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.util.LongUtil;
import com.deroyque.activation.util.ObjectUtil;
import com.deroyque.activation.util.StringUtil;
import com.deroyque.activation.vo.ActivationResultVO;
import com.deroyque.activation.vo.ActivationResultVO.PermanentFailure;
import com.deroyque.activation.vo.ActivationResultVO.Success;
import com.deroyque.activation.vo.ActivationResultVO.TransientFailure;
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
public class ActivationProviderIntercommServiceImpl implements ActivationProviderIntercommService {

    private static final Set<HttpStatus> RETRY_AFTER_STATUSES = Set.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE);
    private static final Set<HttpStatus> CREDENTIAL_REJECTED_STATUSES = Set.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

    private final ActivationProviderClient activationProviderClient;

    private final Clock clock;

    @Override
    public ActivationResultVO activate(ActivationRequest activationRequest) {

        ApiProviderActivationDTO apiProviderActivationDTO = new ApiProviderActivationDTO(activationRequest.getId(), activationRequest.getCustomerId(),
                activationRequest.getProductCode());

        try {

            ResponseEntity<Void> responseEntity = this.activationProviderClient.activate(activationRequest.getId().toString(),
                    CorrelationUtil.getCurrent(), apiProviderActivationDTO);

            return this.classify(responseEntity.getStatusCode(), responseEntity.getHeaders());

        } catch (RestClientResponseException restClientResponseException) {

            return this.classify(restClientResponseException.getStatusCode(), restClientResponseException.getResponseHeaders());

        } catch (ResourceAccessException resourceAccessException) {

            // A timeout is ambiguous: the provider may have activated. The retry sends the same idempotency key.
            String failureReason = this.isTimeout(resourceAccessException) ? FailureReasonConstants.PROVIDER_TIMEOUT
                    : FailureReasonConstants.PROVIDER_UNREACHABLE;

            log.warn("Could not reach the provider for request {}: {} ({})", activationRequest.getId(), failureReason,
                    resourceAccessException.getMessage());

            return new TransientFailure(failureReason);
        }
    }

    private ActivationResultVO classify(HttpStatusCode httpStatusCode, HttpHeaders httpHeaders) {

        if (httpStatusCode.is2xxSuccessful()) {

            return new Success();
        }

        HttpStatus httpStatus = HttpStatus.resolve(httpStatusCode.value());
        Integer statusCode = httpStatusCode.value();

        if (RETRY_AFTER_STATUSES.contains(httpStatus)) {

            String retryAfter = ObjectUtil.isNull(httpHeaders) ? null : httpHeaders.getFirst(HttpHeaders.RETRY_AFTER);

            return new TransientFailure(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode, this.parseRetryAfter(retryAfter));
        }

        if (CREDENTIAL_REJECTED_STATUSES.contains(httpStatus)) {

            log.error("Provider rejected the service credential with status {}; check the secret", statusCode);

            return new TransientFailure(FailureReasonConstants.CREDENTIAL_PREFIX + statusCode);
        }

        if (httpStatus == HttpStatus.REQUEST_TIMEOUT || httpStatusCode.is5xxServerError()) {

            return new TransientFailure(FailureReasonConstants.HTTP_ERROR_PREFIX + statusCode);
        }

        return new PermanentFailure(FailureReasonConstants.REJECTED_PREFIX + statusCode);
    }

    private Duration parseRetryAfter(String retryAfter) {

        if (StringUtil.isNullOrEmpty(retryAfter)) {

            return null;
        }

        Long seconds = LongUtil.toLong(retryAfter);

        if (ObjectUtil.isNotNull(seconds)) {

            return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        }

        try {

            Instant retryAfterDate = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(this.clock.instant(), retryAfterDate);

            return delay.isNegative() ? Duration.ZERO : delay;

        } catch (DateTimeParseException dateTimeParseException) {

            return null;
        }
    }

    private Boolean isTimeout(Throwable throwable) {

        for (Throwable cause = throwable; ObjectUtil.isNotNull(cause); cause = cause.getCause()) {

            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {

                return true;
            }
        }

        return false;
    }

}
