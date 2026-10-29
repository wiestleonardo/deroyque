package com.deroyque.activation.infrastructure.observability;

import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Gauge {@code activation.requests{status=...}}: quantas solicitações há em cada status.
 * Útil para alertar sobre fila crescendo (PENDING) ou presas (PROCESSING). A contagem usa o índice
 * por status e só roda quando o Prometheus coleta.
 */
@Component
public class ActivationStatusMetrics implements MeterBinder {

    private final ActivationRequestRepository repository;

    public ActivationStatusMetrics(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : ActivationStatus.values()) {
            Gauge.builder("activation.requests", repository, r -> r.countByStatus(status))
                    .tag("status", status.name())
                    .description("Solicitações por status")
                    .register(registry);
        }
    }
}
