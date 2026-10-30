package com.deroyque.activation.service.impl;

import com.deroyque.activation.config.ProcessingProperties;
import com.deroyque.activation.constants.FailureReasonConstants;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.exception.IntegrationException;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.service.ActivationProcessingService;
import com.deroyque.activation.util.CorrelationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationProcessingServiceImpl implements ActivationProcessingService {

    private static final List<ActivationStatusEnum> PROCESSABLE_STATUS_LIST = List.of(ActivationStatusEnum.PENDING, ActivationStatusEnum.PROCESSING);

    private final ActivationRequestRepository activationRequestRepository;

    private final ActivationProviderIntercommService activationProviderIntercommService;

    private final ProcessingProperties processingProperties;

    private final Clock clock;

    @Override
    public void processPending() {

        List<UUID> idList = activationRequestRepository.listIdsReadyToProcess(PROCESSABLE_STATUS_LIST, clock.instant(),
                Limit.of(processingProperties.batchSize()));

        for (UUID id : idList) {

            try {

                this.process(id);

            } catch (RuntimeException runtimeException) {

                log.error("Unexpected failure while processing activation request {}", id, runtimeException);
            }
        }
    }

    @Override
    public void process(UUID id) {

        ActivationRequest activationRequest = this.claim(id);

        if (activationRequest == null) {

            return;
        }

        CorrelationUtil.set(activationRequest.getCorrelationId() == null ? id.toString() : activationRequest.getCorrelationId());

        try {

            this.callProvider(activationRequest);

        } finally {

            CorrelationUtil.clear();
        }
    }

    // The provider is called outside any transaction; @Version makes sure only one instance claims and saves each request.
    private ActivationRequest claim(UUID id) {

        ActivationRequest activationRequest = activationRequestRepository.findById(id).orElse(null);
        Instant now = clock.instant();

        if (activationRequest == null) {

            return null;
        }

        if (activationRequest.isLeaseExpired(now)) {

            log.warn("Lease expired for activation request {} after {} attempts", id, activationRequest.getAttempts());

            if (activationRequest.getAttempts() >= processingProperties.maxAttempts()) {

                activationRequest.fail(FailureReasonConstants.MAX_ATTEMPTS_PREFIX + FailureReasonConstants.LEASE_EXPIRED, now);
                this.save(activationRequest);
                return null;
            }

            activationRequest.scheduleRetry(FailureReasonConstants.LEASE_EXPIRED, now, now);
        }

        if (!activationRequest.isReadyToProcess(now)) {

            return null;
        }

        activationRequest.startProcessing(now, processingProperties.leaseDuration());

        return this.save(activationRequest);
    }

    private void callProvider(ActivationRequest activationRequest) {

        try {

            activationProviderIntercommService.activate(activationRequest);
            activationRequest.complete(clock.instant());

            log.info("Activation request {} completed on attempt {}", activationRequest.getId(), activationRequest.getAttempts());

        } catch (IntegrationException integrationException) {

            this.handleFailure(activationRequest, integrationException);
        }

        this.save(activationRequest);
    }

    private void handleFailure(ActivationRequest activationRequest, IntegrationException integrationException) {

        Instant now = clock.instant();

        log.warn("Provider call failed for activation request {} on attempt {}: {}", activationRequest.getId(), activationRequest.getAttempts(),
                integrationException.getReason());

        if (!integrationException.getRetryable()) {

            activationRequest.fail(integrationException.getReason(), now);
            return;
        }

        if (activationRequest.getAttempts() >= processingProperties.maxAttempts()) {

            activationRequest.fail(FailureReasonConstants.MAX_ATTEMPTS_PREFIX + integrationException.getReason(), now);
            return;
        }

        activationRequest.scheduleRetry(integrationException.getReason(),
                now.plus(this.retryDelay(activationRequest.getAttempts(), integrationException.getRetryAfter())), now);
    }

    // Exponential backoff with jitter, capped at maxBackoff; Retry-After from the provider wins when present.
    private Duration retryDelay(Integer attempts, Duration retryAfter) {

        if (retryAfter != null) {

            return retryAfter.compareTo(processingProperties.maxBackoff()) > 0 ? processingProperties.maxBackoff() : retryAfter;
        }

        Duration delay = processingProperties.initialBackoff().multipliedBy(1L << Math.min(attempts - 1, 10));

        if (delay.compareTo(processingProperties.maxBackoff()) > 0) {

            delay = processingProperties.maxBackoff();
        }

        return delay.plusMillis(ThreadLocalRandom.current().nextLong(delay.toMillis() / 2 + 1));
    }

    private ActivationRequest save(ActivationRequest activationRequest) {

        try {

            return activationRequestRepository.saveAndFlush(activationRequest);

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.info("Activation request {} was changed by another instance", activationRequest.getId());

            return null;
        }
    }

}
