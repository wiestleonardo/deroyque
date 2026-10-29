package com.deroyque.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação de um produto para um cliente.
 *
 * <p>O status só muda pelos métodos de domínio (não há setters), que validam a transição.
 * {@code nextAttemptAt} tem dois papéis: em PENDING é quando a solicitação pode ser
 * processada; em PROCESSING é o fim do "lease" do worker que a pegou. Se o worker cair
 * no meio da chamada ao provedor, o lease expira e outra instância reprocessa.</p>
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    public static final int MAX_FAILURE_REASON_LENGTH = 200;

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Lock otimista: impede que dois workers gravem status sobre a mesma versão. */
    @Version
    private Long version;

    protected ActivationRequest() {
        // JPA
    }

    private ActivationRequest(String clientId, String externalId, String customerId,
                              String productCode, String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId);
        this.externalId = Objects.requireNonNull(externalId);
        this.customerId = Objects.requireNonNull(customerId);
        this.productCode = Objects.requireNonNull(productCode);
        this.correlationId = correlationId;
        this.status = ActivationStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ActivationRequest create(String clientId, String externalId, String customerId,
                                           String productCode, String correlationId, Instant now) {
        return new ActivationRequest(clientId, externalId, customerId, productCode, correlationId, now);
    }

    /** Worker assume a solicitação; o lease define até quando ela fica reservada. */
    public void startProcessing(Instant now, Duration lease) {
        transitionTo(ActivationStatus.PROCESSING, now);
        this.attempts++;
        this.nextAttemptAt = now.plus(lease);
    }

    public void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        this.failureReason = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        this.failureReason = truncate(reason);
    }

    /** Falha temporária: volta para a fila e só será tentada de novo em {@code retryAt}. */
    public void scheduleRetry(String reason, Instant retryAt, Instant now) {
        transitionTo(ActivationStatus.PENDING, now);
        this.failureReason = truncate(reason);
        this.nextAttemptAt = retryAt;
    }

    /**
     * Reconciliação: o worker que pegou a solicitação não terminou dentro do lease (caiu ou travou).
     * Não sabemos se o provedor ativou; reenviar é seguro porque a chamada usa chave de idempotência.
     */
    public void releaseExpiredLease(Instant now) {
        if (!isLeaseExpired(now)) {
            throw new IllegalStateException("Lease ainda válido ou solicitação fora de PROCESSING: " + id);
        }
        transitionTo(ActivationStatus.PENDING, now);
        this.failureReason = "PROCESSING_LEASE_EXPIRED";
        this.nextAttemptAt = now;
    }

    public boolean isLeaseExpired(Instant now) {
        return status == ActivationStatus.PROCESSING && !nextAttemptAt.isAfter(now);
    }

    public boolean isDue(Instant now) {
        return status == ActivationStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    /** Usado para diferenciar um retry legítimo de um conflito de conteúdo. */
    public boolean hasSameContent(String customerId, String productCode) {
        return this.customerId.equals(customerId) && this.productCode.equals(productCode);
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
