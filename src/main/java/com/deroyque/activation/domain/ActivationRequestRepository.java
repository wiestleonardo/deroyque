package com.deroyque.activation.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByClientIdAndExternalId(String clientId, String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    /**
     * Solicitações prontas para o worker: PENDING cujo horário chegou ou PROCESSING com lease
     * expirado (worker caiu). Usa o índice (status, next_attempt_at).
     */
    @Query("""
            select r.id from ActivationRequest r
            where r.status in (com.deroyque.activation.domain.ActivationStatus.PENDING,
                               com.deroyque.activation.domain.ActivationStatus.PROCESSING)
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    long countByStatus(ActivationStatus status);
}
