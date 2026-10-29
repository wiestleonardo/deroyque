package com.deroyque.activation.application;

import com.deroyque.activation.application.provider.ActivationProvider;
import com.deroyque.activation.application.provider.ProviderOutcome.PermanentFailure;
import com.deroyque.activation.application.provider.ProviderOutcome.Success;
import com.deroyque.activation.application.provider.ProviderOutcome.TransientFailure;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final ProcessingProperties PROPERTIES =
            new ProcessingProperties(3, Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofMinutes(5), 20);

    private final ActivationRequestRepository repository = mock(ActivationRequestRepository.class);
    private final ActivationProvider provider = mock(ActivationProvider.class);
    private final RetryPolicy retryPolicy = mock(RetryPolicy.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ActivationProcessor processor;
    private ActivationRequest request;

    @BeforeEach
    void setUp() {
        var transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
        processor = new ActivationProcessor(repository, provider, retryPolicy, PROPERTIES, transaction,
                Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
        request = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr", NOW.minusSeconds(60));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void successCompletesRequest() {
        when(provider.activate(request)).thenReturn(new Success());

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(meterRegistry.get("activation.provider.calls").tag("outcome", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void permanentFailureMarksFailedWithoutRetry() {
        when(provider.activate(request)).thenReturn(new PermanentFailure("PROVIDER_REJECTED_400"));

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_REJECTED_400");
        verify(retryPolicy, never()).delayFor(anyInt(), any());
    }

    @Test
    void transientFailureSchedulesRetryWithBackoff() {
        when(provider.activate(request)).thenReturn(new TransientFailure("PROVIDER_HTTP_500"));
        when(retryPolicy.delayFor(1, null)).thenReturn(Duration.ofSeconds(10));

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void transientFailurePassesRetryAfterToPolicy() {
        when(provider.activate(request)).thenReturn(new TransientFailure("PROVIDER_HTTP_429", Duration.ofSeconds(42)));
        when(retryPolicy.delayFor(1, Duration.ofSeconds(42))).thenReturn(Duration.ofSeconds(42));

        processor.process(request.getId());

        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(42));
    }

    @Test
    void transientFailureOnLastAttemptMarksFailed() {
        exhaustAttemptsUntilLast();
        when(provider.activate(request)).thenReturn(new TransientFailure("PROVIDER_TIMEOUT"));

        processor.process(request.getId());

        assertThat(request.getAttempts()).isEqualTo(PROPERTIES.maxAttempts());
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT");
    }

    @Test
    void requestNotDueYetIsSkipped() {
        request.startProcessing(NOW.minusSeconds(5), Duration.ofSeconds(1));
        request.scheduleRetry("PROVIDER_HTTP_500", NOW.plusSeconds(60), NOW.minusSeconds(4));

        processor.process(request.getId());

        verify(provider, never()).activate(any());
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
    }

    @Test
    void terminalRequestIsSkipped() {
        request.startProcessing(NOW.minusSeconds(5), Duration.ofSeconds(1));
        request.complete(NOW.minusSeconds(4));

        processor.process(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void requestClaimedByAnotherInstanceIsSkipped() {
        when(repository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, request.getId()));

        processor.process(request.getId());

        verify(provider, never()).activate(any());
    }

    @Test
    void expiredLeaseIsReclaimedAndProcessed() {
        request.startProcessing(NOW.minusSeconds(60), Duration.ofSeconds(30)); // worker anterior caiu
        when(provider.activate(request)).thenReturn(new Success());

        processor.process(request.getId());

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void expiredLeaseWithoutAttemptsLeftFails() {
        exhaustAttemptsUntilLast();
        request.startProcessing(NOW.minusSeconds(60), Duration.ofSeconds(30));

        processor.process(request.getId());

        verify(provider, never()).activate(any());
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROCESSING_LEASE_EXPIRED");
    }

    /** Deixa a solicitação PENDING e vencida com maxAttempts - 1 tentativas já feitas. */
    private void exhaustAttemptsUntilLast() {
        var past = NOW.minusSeconds(600);
        for (int i = 1; i < PROPERTIES.maxAttempts(); i++) {
            request.startProcessing(past, Duration.ofSeconds(1));
            request.scheduleRetry("PROVIDER_HTTP_500", past, past);
        }
    }
}
