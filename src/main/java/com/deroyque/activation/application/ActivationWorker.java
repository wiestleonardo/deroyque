package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Busca periodicamente solicitações vencidas (PENDING prontas ou PROCESSING com lease expirado)
 * e as processa. Como o estado está no banco, a solicitação gravada é enviada mesmo que a aplicação
 * caia entre o commit do POST e a chamada ao provedor. Pode rodar em várias instâncias: a reserva
 * usa lock otimista.
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivationWorker.class);

    private final ActivationRequestRepository repository;
    private final ActivationProcessor processor;
    private final ProcessingProperties properties;
    private final Clock clock;

    public ActivationWorker(ActivationRequestRepository repository, ActivationProcessor processor,
                            ProcessingProperties properties, Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval:1s}")
    public void poll() {
        var dueIds = repository.findDueIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
        if (dueIds.isEmpty()) {
            return;
        }
        // Chamadas ao provedor são I/O: virtual threads processam o lote em paralelo sem pool dedicado.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            dueIds.forEach(id -> executor.submit(() -> processSafely(id)));
        }
    }

    private void processSafely(UUID id) {
        try {
            processor.process(id);
        } catch (RuntimeException e) {
            // Uma solicitação com problema não pode parar o lote; ela volta pelo lease expirado.
            log.error("Falha inesperada ao processar solicitação {}", id, e);
        }
    }
}
