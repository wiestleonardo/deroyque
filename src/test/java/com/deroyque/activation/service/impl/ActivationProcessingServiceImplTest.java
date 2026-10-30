package com.deroyque.activation.service.impl;

import com.deroyque.activation.config.ProcessingProperties;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.vo.ActivationResultVO.PermanentFailure;
import com.deroyque.activation.vo.ActivationResultVO.Success;
import com.deroyque.activation.vo.ActivationResultVO.TransientFailure;
import com.deroyque.activation.vo.RetryPolicyVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ActivationProcessingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final Integer MAX_ATTEMPTS = 3;
    private static final ProcessingProperties PROCESSING_PROPERTIES = new ProcessingProperties(MAX_ATTEMPTS, Duration.ofSeconds(30),
            Duration.ofSeconds(10), Duration.ofMinutes(5), 20, 5);

    @Mock
    private ActivationRequestRepository activationRequestRepository;

    @Mock
    private ActivationProviderIntercommService activationProviderIntercommService;

    @Mock
    private PlatformTransactionManager platformTransactionManager;

    private final SimpleMeterRegistry simpleMeterRegistry = new SimpleMeterRegistry();

    private ActivationProcessingServiceImpl activationProcessingServiceImpl;

    private ActivationRequest activationRequest;

    @BeforeEach
    void setUp() {

        RetryPolicyVO retryPolicyVO = new RetryPolicyVO(Duration.ofSeconds(10), Duration.ofMinutes(5), new Random(1));

        this.activationProcessingServiceImpl = new ActivationProcessingServiceImpl(activationRequestRepository,
                activationProviderIntercommService, retryPolicyVO, PROCESSING_PROPERTIES, new TransactionTemplate(platformTransactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC), this.simpleMeterRegistry);

        this.activationRequest = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr", NOW.minusSeconds(60));
    }

    @Test
    void shouldCompleteWhenProviderActivates() {

        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest)).willReturn(new Success());

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.COMPLETED);
        assertThat(this.activationRequest.getAttempts()).isEqualTo(1);
        assertThat(this.simpleMeterRegistry.get("activation.provider.calls").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void shouldFailWithoutRetryWhenProviderRejects() {

        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest)).willReturn(new PermanentFailure("PROVIDER_REJECTED_400"));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.FAILED);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("PROVIDER_REJECTED_400");
    }

    @Test
    void shouldScheduleRetryWithBackoffWhenFailureIsTransient() {

        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest)).willReturn(new TransientFailure("PROVIDER_HTTP_500"));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(this.activationRequest.getNextAttemptAt()).isBetween(NOW.plusSeconds(5), NOW.plusSeconds(10));
    }

    @Test
    void shouldHonorRetryAfterWhenProviderAsksToWait() {

        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest))
                .willReturn(new TransientFailure("PROVIDER_HTTP_429", Duration.ofSeconds(42)));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(42));
    }

    @Test
    void shouldFailWhenTransientFailureHappensOnLastAttempt() {

        this.useAttemptsUntilLast();
        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest)).willReturn(new TransientFailure("PROVIDER_TIMEOUT"));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.FAILED);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT");
    }

    @Test
    void shouldSkipWhenNextAttemptIsNotDueYet() {

        this.activationRequest.startProcessing(NOW.minusSeconds(5), Duration.ofSeconds(1));
        this.activationRequest.scheduleRetry("PROVIDER_HTTP_500", NOW.plusSeconds(60), NOW.minusSeconds(4));
        this.stubFind();

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        then(activationProviderIntercommService).shouldHaveNoInteractions();
        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
    }

    @Test
    void shouldSkipWhenRequestIsAlreadyCompleted() {

        this.activationRequest.startProcessing(NOW.minusSeconds(5), Duration.ofSeconds(1));
        this.activationRequest.complete(NOW.minusSeconds(4));
        this.stubFind();

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        then(activationProviderIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void shouldSkipWhenAnotherInstanceClaimsFirst() {

        this.stubFind();
        given(activationRequestRepository.saveAndFlush(this.activationRequest))
                .willThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, this.activationRequest.getId()));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        then(activationProviderIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void shouldProcessAgainWhenLeaseOfAnotherInstanceExpires() {

        this.activationRequest.startProcessing(NOW.minusSeconds(60), Duration.ofSeconds(30));
        this.stubFindAndSave();
        given(activationProviderIntercommService.activate(this.activationRequest)).willReturn(new Success());

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.COMPLETED);
        assertThat(this.activationRequest.getAttempts()).isEqualTo(2);
    }

    @Test
    void shouldFailWithoutCallingProviderWhenLeaseExpiresWithNoAttemptsLeft() {

        this.useAttemptsUntilLast();
        this.activationRequest.startProcessing(NOW.minusSeconds(60), Duration.ofSeconds(30));
        this.stubFindAndSave();

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        then(activationProviderIntercommService).should(never()).activate(any(ActivationRequest.class));
        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.FAILED);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROCESSING_LEASE_EXPIRED");
    }

    private void stubFind() {

        given(activationRequestRepository.findById(this.activationRequest.getId())).willReturn(Optional.of(this.activationRequest));
    }

    private void stubFindAndSave() {

        this.stubFind();
        given(activationRequestRepository.saveAndFlush(this.activationRequest)).willReturn(this.activationRequest);
    }

    private void useAttemptsUntilLast() {

        Instant past = NOW.minusSeconds(600);

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {

            this.activationRequest.startProcessing(past, Duration.ofSeconds(1));
            this.activationRequest.scheduleRetry("PROVIDER_HTTP_500", past, past);
        }
    }

}
