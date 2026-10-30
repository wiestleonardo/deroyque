package com.deroyque.activation.repository.domain;

import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @Test
    void shouldStartPendingAndReadyToProcessWhenCreated() {

        ActivationRequest activationRequest = newActivationRequest();

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(activationRequest.getAttempts()).isZero();
        assertThat(activationRequest.isReadyToProcess(NOW)).isTrue();
    }

    @Test
    void shouldCountAttemptAndSetLeaseWhenProcessingStarts() {

        ActivationRequest activationRequest = processingActivationRequest();

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PROCESSING);
        assertThat(activationRequest.getAttempts()).isEqualTo(1);
        assertThat(activationRequest.isLeaseExpired(NOW)).isFalse();
        assertThat(activationRequest.isLeaseExpired(NOW.plus(LEASE_DURATION))).isTrue();
    }

    @Test
    void shouldGoBackToPendingWithNextAttemptWhenRetryIsScheduled() {

        ActivationRequest activationRequest = processingActivationRequest();

        activationRequest.scheduleRetry("PROVIDER_HTTP_500", NOW.plusSeconds(10), NOW);

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(activationRequest.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(activationRequest.isReadyToProcess(NOW)).isFalse();
        assertThat(activationRequest.isReadyToProcess(NOW.plusSeconds(10))).isTrue();
    }

    @Test
    void shouldCompareCustomerAndProductWhenCheckingSameContent() {

        ActivationRequest activationRequest = newActivationRequest();

        assertThat(activationRequest.hasSameContent("CUST-1", "INTERNET-500")).isTrue();
        assertThat(activationRequest.hasSameContent("CUST-1", "TV-100")).isFalse();
    }

    @Test
    void shouldRejectCompletingWithoutProcessing() {

        ActivationRequest activationRequest = newActivationRequest();

        assertInvalidTransition(() -> activationRequest.complete(NOW));
    }

    @Test
    void shouldRejectCompletedGoingBackToProcessing() {

        ActivationRequest activationRequest = processingActivationRequest();
        activationRequest.complete(NOW);

        assertInvalidTransition(() -> activationRequest.startProcessing(NOW, LEASE_DURATION));
    }

    @Test
    void shouldRejectRetryAfterFailed() {

        ActivationRequest activationRequest = processingActivationRequest();
        activationRequest.fail("PROVIDER_REJECTED_400", NOW);

        assertInvalidTransition(() -> activationRequest.scheduleRetry("PROVIDER_HTTP_500", NOW, NOW));
    }

    private static ActivationRequest newActivationRequest() {

        return ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", NOW);
    }

    private static ActivationRequest processingActivationRequest() {

        ActivationRequest activationRequest = newActivationRequest();
        activationRequest.startProcessing(NOW, LEASE_DURATION);

        return activationRequest;
    }

    private static void assertInvalidTransition(Runnable action) {

        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessServiceException.class)
                .extracting(exception -> ((BusinessServiceException) exception).getErrorEnum())
                .isEqualTo(ErrorEnum.INVALID_STATUS_TRANSITION);
    }

}
