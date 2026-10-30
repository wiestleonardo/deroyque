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
    private static final String SERVER_ERROR_REASON = "PROVIDER_HTTP_500";

    @Test
    void shouldStartPendingAndReadyToProcessWhenCreated() {

        ActivationRequest activationRequest = newActivationRequest();

        assertThat(activationRequest.getId()).isNotNull();
        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(activationRequest.getAttempts()).isZero();
        assertThat(activationRequest.getCreatedAt()).isEqualTo(NOW);
        assertThat(activationRequest.getUpdatedAt()).isEqualTo(NOW);
        assertThat(activationRequest.isReadyToProcess(NOW)).isTrue();
    }

    @Test
    void shouldCountAttemptAndSetLeaseWhenProcessingStarts() {

        ActivationRequest activationRequest = newActivationRequest();

        activationRequest.startProcessing(NOW, LEASE_DURATION);

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PROCESSING);
        assertThat(activationRequest.getAttempts()).isEqualTo(1);
        assertThat(activationRequest.getNextAttemptAt()).isEqualTo(NOW.plus(LEASE_DURATION));
        assertThat(activationRequest.isLeaseExpired(NOW)).isFalse();
        assertThat(activationRequest.isLeaseExpired(NOW.plus(LEASE_DURATION))).isTrue();
    }

    @Test
    void shouldBeCompletedWhenProcessingSucceeds() {

        ActivationRequest activationRequest = processingActivationRequest();

        activationRequest.complete(NOW.plusSeconds(1));

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.COMPLETED);
        assertThat(activationRequest.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void shouldGoBackToPendingWithNextAttemptWhenFailureIsTransient() {

        ActivationRequest activationRequest = processingActivationRequest();

        activationRequest.scheduleRetry(SERVER_ERROR_REASON, NOW.plusSeconds(10), NOW);

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(activationRequest.getFailureReason()).isEqualTo(SERVER_ERROR_REASON);
        assertThat(activationRequest.isReadyToProcess(NOW)).isFalse();
        assertThat(activationRequest.isReadyToProcess(NOW.plusSeconds(10))).isTrue();
    }

    @Test
    void shouldReturnToQueueWhenLeaseExpires() {

        ActivationRequest activationRequest = processingActivationRequest();
        Instant afterLease = NOW.plus(LEASE_DURATION).plusSeconds(1);

        activationRequest.releaseExpiredLease("PROCESSING_LEASE_EXPIRED", afterLease);

        assertThat(activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(activationRequest.getFailureReason()).isEqualTo("PROCESSING_LEASE_EXPIRED");
        assertThat(activationRequest.isReadyToProcess(afterLease)).isTrue();
        assertThat(activationRequest.getAttempts()).isEqualTo(1);
    }

    @Test
    void shouldThrowWhenReleasingLeaseThatIsStillValid() {

        ActivationRequest activationRequest = processingActivationRequest();

        assertInvalidTransition(() -> activationRequest.releaseExpiredLease("PROCESSING_LEASE_EXPIRED", NOW.plusSeconds(1)));
    }

    @Test
    void shouldTruncateWhenFailureReasonIsTooLong() {

        ActivationRequest activationRequest = processingActivationRequest();

        activationRequest.fail("x".repeat(500), NOW);

        assertThat(activationRequest.getFailureReason()).hasSize(ActivationRequest.MAX_FAILURE_REASON_LENGTH);
    }

    @Test
    void shouldCompareCustomerAndProductWhenCheckingSameContent() {

        ActivationRequest activationRequest = newActivationRequest();

        assertThat(activationRequest.hasSameContent("CUST-1", "INTERNET-500")).isTrue();
        assertThat(activationRequest.hasSameContent("CUST-1", "TV-100")).isFalse();
        assertThat(activationRequest.hasSameContent("CUST-2", "INTERNET-500")).isFalse();
    }

    @Test
    void shouldThrowWhenCompletingWithoutProcessing() {

        ActivationRequest activationRequest = newActivationRequest();

        assertInvalidTransition(() -> activationRequest.complete(NOW));
    }

    @Test
    void shouldThrowWhenFailingWithoutProcessing() {

        ActivationRequest activationRequest = newActivationRequest();

        assertInvalidTransition(() -> activationRequest.fail(SERVER_ERROR_REASON, NOW));
    }

    @Test
    void shouldThrowWhenCompletedGoesBackToProcessing() {

        ActivationRequest activationRequest = processingActivationRequest();
        activationRequest.complete(NOW);

        assertInvalidTransition(() -> activationRequest.startProcessing(NOW, LEASE_DURATION));
    }

    @Test
    void shouldThrowWhenFailedIsScheduledForRetry() {

        ActivationRequest activationRequest = processingActivationRequest();
        activationRequest.fail("PROVIDER_REJECTED_400", NOW);

        assertInvalidTransition(() -> activationRequest.scheduleRetry(SERVER_ERROR_REASON, NOW, NOW));
        assertThat(activationRequest.getStatus().isFinal()).isTrue();
    }

    @Test
    void shouldThrowWhenProcessingStartsTwice() {

        ActivationRequest activationRequest = processingActivationRequest();

        assertInvalidTransition(() -> activationRequest.startProcessing(NOW, LEASE_DURATION));
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
