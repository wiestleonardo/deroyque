package com.deroyque.activation.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "processing")
public record ProcessingProperties(

        @NotNull
        @Positive
        Integer maxAttempts,

        @NotNull
        Duration leaseDuration,

        @NotNull
        Duration initialBackoff,

        @NotNull
        Duration maxBackoff,

        @NotNull
        @Positive
        Integer batchSize,

        @NotNull
        @Positive
        Integer concurrencyLimit) {

}
