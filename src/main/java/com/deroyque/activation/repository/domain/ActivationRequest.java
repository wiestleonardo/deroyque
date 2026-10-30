package com.deroyque.activation.repository.domain;

import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.util.IntegerUtil;
import com.deroyque.activation.util.ObjectUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "tb_activationrequest")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivationRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final Integer MAX_FAILURE_REASON_LENGTH = 200;

    @Id
    @Column(name = "id_activationrequest", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cd_sourcesystem", nullable = false, updatable = false)
    private String sourceSystem;

    @Column(name = "cd_external", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "cd_customer", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "cd_product", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "fl_status", nullable = false)
    private ActivationStatusEnum status;

    @Column(name = "qt_attempt", nullable = false)
    private Integer attempts;

    @Column(name = "dt_nextattempt", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "tx_failurereason")
    private String failureReason;

    @Column(name = "cd_correlation", updatable = false)
    private String correlationId;

    @Column(name = "dt_created", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dt_updated", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "nr_version", nullable = false)
    private Long version;

    public static ActivationRequest create(String sourceSystem, String externalId, String customerId, String productCode, String correlationId,
            Instant now) {

        ActivationRequest activationRequest = new ActivationRequest();
        activationRequest.id = UUID.randomUUID();
        activationRequest.sourceSystem = sourceSystem;
        activationRequest.externalId = externalId;
        activationRequest.customerId = customerId;
        activationRequest.productCode = productCode;
        activationRequest.correlationId = correlationId;
        activationRequest.status = ActivationStatusEnum.PENDING;
        activationRequest.attempts = IntegerUtil.ZERO;
        activationRequest.nextAttemptAt = now;
        activationRequest.createdAt = now;
        activationRequest.updatedAt = now;

        return activationRequest;
    }

    public void startProcessing(Instant now, Duration leaseDuration) {

        this.transitionTo(ActivationStatusEnum.PROCESSING, now);
        this.attempts = IntegerUtil.sum(this.attempts, IntegerUtil.ONE);
        this.nextAttemptAt = now.plus(leaseDuration);
    }

    public void complete(Instant now) {

        this.transitionTo(ActivationStatusEnum.COMPLETED, now);
        this.failureReason = null;
    }

    public void fail(String failureReason, Instant now) {

        this.transitionTo(ActivationStatusEnum.FAILED, now);
        this.failureReason = truncate(failureReason);
    }

    public void scheduleRetry(String failureReason, Instant nextAttemptAt, Instant now) {

        this.transitionTo(ActivationStatusEnum.PENDING, now);
        this.failureReason = truncate(failureReason);
        this.nextAttemptAt = nextAttemptAt;
    }

    public void releaseExpiredLease(String failureReason, Instant now) {

        if (!this.isLeaseExpired(now)) {

            throw new BusinessServiceException(ErrorEnum.INVALID_STATUS_TRANSITION, this.status, ActivationStatusEnum.PENDING);
        }

        this.scheduleRetry(failureReason, now, now);
    }

    public Boolean isLeaseExpired(Instant now) {

        return this.status == ActivationStatusEnum.PROCESSING && !this.nextAttemptAt.isAfter(now);
    }

    public Boolean isReadyToProcess(Instant now) {

        return this.status == ActivationStatusEnum.PENDING && !this.nextAttemptAt.isAfter(now);
    }

    public Boolean hasSameContent(String customerId, String productCode) {

        return ObjectUtil.isEquals(this.customerId, customerId) && ObjectUtil.isEquals(this.productCode, productCode);
    }

    private void transitionTo(ActivationStatusEnum targetStatus, Instant now) {

        if (!this.status.canTransitionTo(targetStatus)) {

            throw new BusinessServiceException(ErrorEnum.INVALID_STATUS_TRANSITION, this.status, targetStatus);
        }

        this.status = targetStatus;
        this.updatedAt = now;
    }

    private static String truncate(String failureReason) {

        if (ObjectUtil.isNull(failureReason) || failureReason.length() <= MAX_FAILURE_REASON_LENGTH) {

            return failureReason;
        }

        return failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

}
