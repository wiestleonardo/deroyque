package com.deroyque.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxAttempts    chamadas ao provedor antes de desistir (FAILED)
 * @param lease          tempo que um worker reserva a solicitação; deve ser maior que
 *                       connect-timeout + read-timeout do provedor
 * @param initialBackoff espera antes da 2ª tentativa; dobra a cada falha
 * @param maxBackoff     teto da espera (inclusive para Retry-After)
 * @param batchSize      solicitações por ciclo do worker
 */
@ConfigurationProperties("app.processing")
public record ProcessingProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("2s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("20") int batchSize) {
}
