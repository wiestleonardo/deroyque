package com.deroyque.activation.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private ActivationRequest newRequest() {
        return ActivationRequest.create("client-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", NOW);
    }

    @Test
    void newRequestStartsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
        assertThat(request.isDue(NOW)).isTrue();
    }

    @Test
    void startProcessingCountsAttemptAndSetsLease() {
        var request = newRequest();

        request.startProcessing(NOW, LEASE);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(request.isLeaseExpired(NOW)).isFalse();
        assertThat(request.isLeaseExpired(NOW.plus(LEASE))).isTrue();
    }

    @Test
    void happyPathEndsCompleted() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        request.complete(NOW.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void temporaryFailureGoesBackToPendingWithNextAttempt() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        request.scheduleRetry("PROVIDER_HTTP_500", NOW.plusSeconds(10), NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(request.isDue(NOW)).isFalse();
        assertThat(request.isDue(NOW.plusSeconds(10))).isTrue();
    }

    @Test
    void expiredLeaseReturnsRequestToQueue() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);
        var afterLease = NOW.plus(LEASE).plusSeconds(1);

        request.releaseExpiredLease(afterLease);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("PROCESSING_LEASE_EXPIRED");
        assertThat(request.isDue(afterLease)).isTrue();
        assertThat(request.getAttempts()).isEqualTo(1);
    }

    @Test
    void leaseCannotBeReleasedWhileValid() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        assertThatThrownBy(() -> request.releaseExpiredLease(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureReasonIsTruncated() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        request.fail("x".repeat(500), NOW);

        assertThat(request.getFailureReason()).hasSize(ActivationRequest.MAX_FAILURE_REASON_LENGTH);
    }

    @Test
    void sameContentComparesCustomerAndProduct() {
        var request = newRequest();

        assertThat(request.hasSameContent("CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.hasSameContent("CUST-1", "TV-100")).isFalse();
        assertThat(request.hasSameContent("CUST-2", "INTERNET-500")).isFalse();
    }

    @Nested
    class InvalidTransitions {

        @Test
        void pendingCannotCompleteWithoutProcessing() {
            var request = newRequest();

            assertThatThrownBy(() -> request.complete(NOW))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("PENDING -> COMPLETED");
        }

        @Test
        void pendingCannotFailWithoutProcessing() {
            var request = newRequest();

            assertThatThrownBy(() -> request.fail("x", NOW))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void completedNeverGoesBackToProcessing() {
            var request = newRequest();
            request.startProcessing(NOW, LEASE);
            request.complete(NOW);

            assertThatThrownBy(() -> request.startProcessing(NOW, LEASE))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("COMPLETED -> PROCESSING");
        }

        @Test
        void failedIsTerminal() {
            var request = newRequest();
            request.startProcessing(NOW, LEASE);
            request.fail("PROVIDER_HTTP_400", NOW);

            assertThatThrownBy(() -> request.scheduleRetry("x", NOW, NOW))
                    .isInstanceOf(InvalidStatusTransitionException.class);
            assertThat(ActivationStatus.FAILED.isTerminal()).isTrue();
        }

        @Test
        void processingCannotStartAgainWithoutReleasingLease() {
            var request = newRequest();
            request.startProcessing(NOW, LEASE);

            assertThatThrownBy(() -> request.startProcessing(NOW, LEASE))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }
    }
}
