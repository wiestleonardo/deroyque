package com.deroyque.activation.application;

import com.deroyque.activation.application.provider.ActivationProvider;
import com.deroyque.activation.application.provider.ProviderOutcome;
import com.deroyque.activation.application.provider.ProviderOutcome.PermanentFailure;
import com.deroyque.activation.application.provider.ProviderOutcome.Success;
import com.deroyque.activation.application.provider.ProviderOutcome.TransientFailure;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.shared.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Processa uma solicitação em três passos, sem nunca segurar transação aberta durante a chamada HTTP:
 * <ol>
 *     <li>reserva (transação curta): PENDING → PROCESSING com lease; o lock otimista garante que só
 *     uma instância vence;</li>
 *     <li>chamada ao provedor, fora de transação;</li>
 *     <li>aplica o resultado (transação curta), só se a versão ainda for a que reservamos.</li>
 * </ol>
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final RetryPolicy retryPolicy;
    private final ProcessingProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationProvider provider,
                               RetryPolicy retryPolicy, ProcessingProperties properties,
                               TransactionTemplate transaction, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.provider = provider;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public void process(UUID id) {
        var claimed = claim(id);
        if (claimed == null) {
            return;
        }
        MDC.put(CorrelationId.MDC_KEY, Objects.requireNonNullElse(claimed.getCorrelationId(), id.toString()));
        try {
            var outcome = callProvider(claimed);
            apply(claimed, outcome);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** @return a solicitação reservada, ou {@code null} se não há nada a fazer (ou outra instância pegou). */
    private ActivationRequest claim(UUID id) {
        try {
            return transaction.execute(status -> {
                var request = repository.findById(id).orElse(null);
                if (request == null) {
                    return null;
                }
                var now = clock.instant();
                if (request.isLeaseExpired(now)) {
                    log.warn("Lease expirado id={} tentativas={}", id, request.getAttempts());
                    if (request.getAttempts() >= properties.maxAttempts()) {
                        request.fail("MAX_ATTEMPTS_EXCEEDED:PROCESSING_LEASE_EXPIRED", now);
                        repository.saveAndFlush(request);
                        countTransition(request);
                        return null;
                    }
                    request.releaseExpiredLease(now);
                }
                if (!request.isDue(now)) {
                    return null;
                }
                request.startProcessing(now, properties.lease());
                return repository.saveAndFlush(request);
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            log.debug("Solicitação {} reservada por outra instância", id);
            return null;
        }
    }

    private ProviderOutcome callProvider(ActivationRequest request) {
        var sample = Timer.start(meterRegistry);
        var outcome = provider.activate(request);
        sample.stop(meterRegistry.timer("activation.provider.calls", "outcome", outcomeTag(outcome)));
        log.info("Provedor respondeu id={} tentativa={} resultado={}", request.getId(), request.getAttempts(), outcome);
        return outcome;
    }

    private void apply(ActivationRequest claimed, ProviderOutcome outcome) {
        try {
            transaction.executeWithoutResult(status -> {
                var request = repository.findById(claimed.getId()).orElseThrow();
                if (!Objects.equals(request.getVersion(), claimed.getVersion())) {
                    // O lease expirou e outra instância assumiu: o resultado dela prevalece.
                    log.warn("Resultado descartado; solicitação {} alterada por outra instância", request.getId());
                    return;
                }
                var now = clock.instant();
                switch (outcome) {
                    case Success ignored -> request.complete(now);
                    case PermanentFailure failure -> request.fail(failure.reason(), now);
                    case TransientFailure failure when request.getAttempts() >= properties.maxAttempts() ->
                            request.fail("MAX_ATTEMPTS_EXCEEDED:" + failure.reason(), now);
                    case TransientFailure failure -> request.scheduleRetry(failure.reason(),
                            now.plus(retryPolicy.delayFor(request.getAttempts(), failure.retryAfter())), now);
                }
                repository.saveAndFlush(request);
                countTransition(request);
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Conflito ao gravar resultado da solicitação {}; será reconciliada", claimed.getId());
        }
    }

    private void countTransition(ActivationRequest request) {
        meterRegistry.counter("activation.requests.transitions", "status", request.getStatus().name()).increment();
    }

    private static String outcomeTag(ProviderOutcome outcome) {
        return switch (outcome) {
            case Success ignored -> "success";
            case PermanentFailure ignored -> "permanent_failure";
            case TransientFailure ignored -> "transient_failure";
        };
    }
}
