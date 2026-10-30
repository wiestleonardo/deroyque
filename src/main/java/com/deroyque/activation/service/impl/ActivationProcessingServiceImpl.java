package com.deroyque.activation.service.impl;

import com.deroyque.activation.config.ProcessingProperties;
import com.deroyque.activation.constants.FailureReasonConstants;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.service.ActivationProcessingService;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.util.IntegerUtil;
import com.deroyque.activation.util.ObjectUtil;
import com.deroyque.activation.vo.ActivationResultVO;
import com.deroyque.activation.vo.ActivationResultVO.PermanentFailure;
import com.deroyque.activation.vo.ActivationResultVO.Success;
import com.deroyque.activation.vo.ActivationResultVO.TransientFailure;
import com.deroyque.activation.vo.RetryPolicyVO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationProcessingServiceImpl implements ActivationProcessingService {

    private static final List<ActivationStatusEnum> PROCESSABLE_STATUS_LIST = List.of(ActivationStatusEnum.PENDING, ActivationStatusEnum.PROCESSING);
    private static final String PROVIDER_CALLS_METRIC = "activation.provider.calls";
    private static final String TRANSITIONS_METRIC = "activation.requests.transitions";
    private static final String OUTCOME_TAG = "outcome";
    private static final String STATUS_TAG = "status";

    private final ActivationRequestRepository activationRequestRepository;

    private final ActivationProviderIntercommService activationProviderIntercommService;

    private final RetryPolicyVO retryPolicyVO;

    private final ProcessingProperties processingProperties;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    private final MeterRegistry meterRegistry;

    @Override
    public void processPending() {

        List<UUID> idList = this.activationRequestRepository.listIdsReadyToProcess(PROCESSABLE_STATUS_LIST, this.clock.instant(),
                Limit.of(this.processingProperties.batchSize()));

        if (idList.isEmpty()) {

            return;
        }

        // Kept below the connection pool size: virtual threads do not limit how many tasks ask for a connection at once.
        Semaphore semaphore = new Semaphore(this.processingProperties.concurrencyLimit());

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            for (UUID id : idList) {

                executorService.submit(() -> this.processWithLimit(id, semaphore));
            }
        }
    }

    @Override
    public void process(UUID id) {

        Optional<ActivationRequest> claimedActivationRequest = this.claim(id);

        if (claimedActivationRequest.isEmpty()) {

            return;
        }

        ActivationRequest activationRequest = claimedActivationRequest.get();
        String correlationId = activationRequest.getCorrelationId();

        CorrelationUtil.set(ObjectUtil.isNull(correlationId) ? id.toString() : correlationId);

        try {

            ActivationResultVO activationResultVO = this.callProvider(activationRequest);

            this.applyResult(activationRequest, activationResultVO);

        } finally {

            CorrelationUtil.clear();
        }
    }

    private void processWithLimit(UUID id, Semaphore semaphore) {

        try {

            semaphore.acquire();

        } catch (InterruptedException interruptedException) {

            Thread.currentThread().interrupt();
            return;
        }

        try {

            this.process(id);

        } catch (RuntimeException runtimeException) {

            log.error("Unexpected failure while processing activation request {}", id, runtimeException);

        } finally {

            semaphore.release();
        }
    }

    private Optional<ActivationRequest> claim(UUID id) {

        try {

            return this.transactionTemplate.execute(transactionStatus -> {

                Optional<ActivationRequest> foundActivationRequest = this.activationRequestRepository.findById(id);

                if (foundActivationRequest.isEmpty()) {

                    return Optional.empty();
                }

                ActivationRequest activationRequest = foundActivationRequest.get();
                Instant now = this.clock.instant();

                if (activationRequest.isLeaseExpired(now)) {

                    log.warn("Lease expired for activation request {} after {} attempts", id, activationRequest.getAttempts());

                    if (this.isOutOfAttempts(activationRequest)) {

                        activationRequest.fail(FailureReasonConstants.MAX_ATTEMPTS_PREFIX + FailureReasonConstants.LEASE_EXPIRED, now);
                        this.activationRequestRepository.saveAndFlush(activationRequest);
                        this.countTransition(activationRequest);

                        return Optional.empty();
                    }

                    activationRequest.releaseExpiredLease(FailureReasonConstants.LEASE_EXPIRED, now);
                }

                if (!activationRequest.isReadyToProcess(now)) {

                    return Optional.empty();
                }

                activationRequest.startProcessing(now, this.processingProperties.leaseDuration());

                return Optional.of(this.activationRequestRepository.saveAndFlush(activationRequest));
            });

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.debug("Activation request {} was claimed by another instance", id);

            return Optional.empty();
        }
    }

    private ActivationResultVO callProvider(ActivationRequest activationRequest) {

        Timer.Sample sample = Timer.start(this.meterRegistry);

        ActivationResultVO activationResultVO = this.activationProviderIntercommService.activate(activationRequest);

        sample.stop(this.meterRegistry.timer(PROVIDER_CALLS_METRIC, OUTCOME_TAG, this.getOutcomeTag(activationResultVO)));

        log.info("Provider returned {} for activation request {} on attempt {}", activationResultVO, activationRequest.getId(),
                activationRequest.getAttempts());

        return activationResultVO;
    }

    private void applyResult(ActivationRequest claimedActivationRequest, ActivationResultVO activationResultVO) {

        try {

            this.transactionTemplate.executeWithoutResult(transactionStatus -> {

                ActivationRequest activationRequest = this.activationRequestRepository.findById(claimedActivationRequest.getId()).orElseThrow();

                if (!ObjectUtil.isEquals(activationRequest.getVersion(), claimedActivationRequest.getVersion())) {

                    log.warn("Result discarded: activation request {} was taken over by another instance", activationRequest.getId());
                    return;
                }

                Instant now = this.clock.instant();

                switch (activationResultVO) {
                    case Success success -> activationRequest.complete(now);
                    case PermanentFailure permanentFailure -> activationRequest.fail(permanentFailure.reason(), now);
                    case TransientFailure transientFailure when this.isOutOfAttempts(activationRequest) ->
                            activationRequest.fail(FailureReasonConstants.MAX_ATTEMPTS_PREFIX + transientFailure.reason(), now);
                    case TransientFailure transientFailure -> activationRequest.scheduleRetry(transientFailure.reason(),
                            now.plus(this.retryPolicyVO.calculateDelay(activationRequest.getAttempts(), transientFailure.retryAfter())), now);
                }

                this.activationRequestRepository.saveAndFlush(activationRequest);
                this.countTransition(activationRequest);
            });

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.warn("Conflict while saving the result of activation request {}; it will be processed again", claimedActivationRequest.getId());
        }
    }

    private Boolean isOutOfAttempts(ActivationRequest activationRequest) {

        return IntegerUtil.greaterOrEqual(activationRequest.getAttempts(), this.processingProperties.maxAttempts());
    }

    private void countTransition(ActivationRequest activationRequest) {

        this.meterRegistry.counter(TRANSITIONS_METRIC, STATUS_TAG, activationRequest.getStatus().name()).increment();
    }

    private String getOutcomeTag(ActivationResultVO activationResultVO) {

        return switch (activationResultVO) {
            case Success success -> "success";
            case PermanentFailure permanentFailure -> "permanent_failure";
            case TransientFailure transientFailure -> "transient_failure";
        };
    }

}
