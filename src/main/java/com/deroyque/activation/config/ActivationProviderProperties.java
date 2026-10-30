package com.deroyque.activation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "intercomm.activation-provider")
public record ActivationProviderProperties(

        @NotBlank
        String baseUrl,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout,

        String apiKey) {

    @Override
    public String toString() {

        return "ActivationProviderProperties[baseUrl=%s, connectTimeout=%s, readTimeout=%s, apiKey=***]"
                .formatted(this.baseUrl, this.connectTimeout, this.readTimeout);
    }

}
