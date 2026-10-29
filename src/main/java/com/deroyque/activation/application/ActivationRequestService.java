package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Cria a solicitação de forma idempotente por (clientId, externalId).
     *
     * <p>A consulta prévia é só um atalho para o retry comum. Quem garante a unicidade é a
     * constraint no banco: se duas requisições passarem juntas pela consulta, só um INSERT
     * vence e o outro recebe violação de constraint, que tratamos relendo o registro vencedor.
     * Por isso o método não é {@code @Transactional}: o INSERT roda (e falha) na própria
     * transação, e a releitura acontece numa transação nova, que enxerga o registro já commitado.</p>
     *
     * <p>A chamada ao provedor não acontece aqui: o registro PENDING já é a "outbox" que o
     * worker processa depois do commit.</p>
     */
    public CreationResult create(CreateActivationCommand command) {
        var existing = repository.findByClientIdAndExternalId(command.clientId(), command.externalId());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), command);
        }

        var request = ActivationRequest.create(
                command.clientId(), command.externalId(), command.customerId(),
                command.productCode(), command.correlationId(), clock.instant());
        try {
            var saved = repository.saveAndFlush(request);
            log.info("Solicitação criada id={} externalId={}", saved.getId(), saved.getExternalId());
            return new CreationResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            var winner = repository.findByClientIdAndExternalId(command.clientId(), command.externalId())
                    .orElseThrow(() -> e);
            log.info("Corrida de criação resolvida pela constraint externalId={}", command.externalId());
            return resolveExisting(winner, command);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(String clientId, UUID id) {
        // Busca sempre pelo dono: solicitação de outro consumidor é tratada como inexistente (BOLA).
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new RequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(String clientId, ActivationStatus status, String customerId,
                                          Pageable pageable) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null && !customerId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        return repository.findAll(spec, pageable);
    }

    private CreationResult resolveExisting(ActivationRequest existing, CreateActivationCommand command) {
        if (existing.hasSameContent(command.customerId(), command.productCode())) {
            return new CreationResult(existing, false);
        }
        throw new DuplicateRequestConflictException(command.externalId(), existing.getId());
    }
}
