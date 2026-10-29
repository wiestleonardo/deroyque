package com.deroyque.activation.infrastructure.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param apiKey credencial do provedor; vem de variável de ambiente/secret manager, nunca do repositório
 */
@ConfigurationProperties("app.provider")
public record ProviderProperties(
        @DefaultValue("http://localhost:8089") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout,
        String apiKey) {

    @Override
    public String toString() {
        return "ProviderProperties[baseUrl=%s, connectTimeout=%s, readTimeout=%s, apiKey=***]"
                .formatted(baseUrl, connectTimeout, readTimeout);
    }
}
