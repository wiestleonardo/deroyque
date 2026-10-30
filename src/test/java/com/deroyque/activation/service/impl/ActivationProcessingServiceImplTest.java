package com.deroyque.activation.service.impl;

import com.deroyque.activation.config.ProcessingProperties;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.exception.IntegrationException;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.repository.domain.ActivationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ActivationProcessingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final ProcessingProperties PROCESSING_PROPERTIES = new ProcessingProperties(3, Duration.ofSeconds(30), Duration.ofSeconds(10),
            Duration.ofMinutes(5), 20);

    @Mock
    private ActivationRequestRepository activationRequestRepository;

    @Mock
    private ActivationProviderIntercommService activationProviderIntercommService;

    private ActivationProcessingServiceImpl activationProcessingServiceImpl;

    private ActivationRequest activationRequest;

    @BeforeEach
    void setUp() {

        this.activationProcessingServiceImpl = new ActivationProcessingServiceImpl(activationRequestRepository, activationProviderIntercommService,
                PROCESSING_PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
        this.activationRequest = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr", NOW.minusSeconds(60));
    }

    @Test
    void shouldCompleteWhenProviderActivates() {

        this.stubFindAndSave();

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.COMPLETED);
        assertThat(this.activationRequest.getAttempts()).isEqualTo(1);
    }

    @Test
    void shouldFailWithoutRetryWhenProviderRejects() {

        this.stubFindAndSave();
        this.stubProviderFailure(new IntegrationException("PROVIDER_REJECTED_400", false, null));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.FAILED);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("PROVIDER_REJECTED_400");
    }

    @Test
    void shouldScheduleRetryWithBackoffWhenFailureIsRetryable() {

        this.stubFindAndSave();
        this.stubProviderFailure(new IntegrationException("PROVIDER_HTTP_500", true, null));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.PENDING);
        assertThat(this.activationRequest.getNextAttemptAt()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(15));
    }

    @Test
    void shouldUseRetryAfterWhenProviderSendsIt() {

        this.stubFindAndSave();
        this.stubProviderFailure(new IntegrationException("PROVIDER_HTTP_503", true, Duration.ofSeconds(42)));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(42));
    }

    @Test
    void shouldFailWhenLastAttemptFails() {

        this.useAllAttemptsButOne();
        this.stubFindAndSave();
        this.stubProviderFailure(new IntegrationException("PROVIDER_TIMEOUT", true, null));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.FAILED);
        assertThat(this.activationRequest.getFailureReason()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT");
    }

    @Test
    void shouldSkipWhenAnotherInstanceClaimsFirst() {

        given(activationRequestRepository.findById(this.activationRequest.getId())).willReturn(Optional.of(this.activationRequest));
        given(activationRequestRepository.saveAndFlush(this.activationRequest))
                .willThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, this.activationRequest.getId()));

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        then(activationProviderIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void shouldProcessAgainWhenLeaseExpires() {

        this.activationRequest.startProcessing(NOW.minusSeconds(60), Duration.ofSeconds(30));
        this.stubFindAndSave();

        this.activationProcessingServiceImpl.process(this.activationRequest.getId());

        assertThat(this.activationRequest.getStatus()).isEqualTo(ActivationStatusEnum.COMPLETED);
        assertThat(this.activationRequest.getAttempts()).isEqualTo(2);
    }

    private void stubFindAndSave() {

        given(activationRequestRepository.findById(this.activationRequest.getId())).willReturn(Optional.of(this.activationRequest));
        given(activationRequestRepository.saveAndFlush(this.activationRequest)).willReturn(this.activationRequest);
    }

    private void stubProviderFailure(IntegrationException integrationException) {

        willThrow(integrationException).given(activationProviderIntercommService).activate(any(ActivationRequest.class));
    }

    private void useAllAttemptsButOne() {

        Instant past = NOW.minusSeconds(600);

        for (int attempt = 1; attempt < PROCESSING_PROPERTIES.maxAttempts(); attempt++) {

            this.activationRequest.startProcessing(past, Duration.ofSeconds(1));
            this.activationRequest.scheduleRetry("PROVIDER_HTTP_500", past, past);
        }
    }

}
