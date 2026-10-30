package com.deroyque.activation.repository;

import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.repository.domain.ActivationRequest;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

    public abstract Optional<ActivationRequest> findBySourceSystemAndExternalId(String sourceSystem, String externalId);

    public abstract Optional<ActivationRequest> findByIdAndSourceSystem(UUID id, String sourceSystem);

    public abstract Long countByStatus(ActivationStatusEnum status);

    @Query("""
            SELECT activationRequest
              FROM ActivationRequest activationRequest
             WHERE activationRequest.sourceSystem = :sourceSystem
               AND (:status IS NULL OR activationRequest.status = :status)
               AND (:customerId IS NULL OR activationRequest.customerId = :customerId)
            """)
    public abstract Page<ActivationRequest> listByFilter(String sourceSystem, ActivationStatusEnum status, String customerId, Pageable pageable);

    @Query("""
            SELECT activationRequest.id
              FROM ActivationRequest activationRequest
             WHERE activationRequest.status IN :statusList
               AND activationRequest.nextAttemptAt <= :now
             ORDER BY activationRequest.nextAttemptAt
            """)
    public abstract List<UUID> listIdsReadyToProcess(List<ActivationStatusEnum> statusList, Instant now, Limit limit);

}
