package com.deroyque.activation.localdev;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Apenas para desenvolvimento local / docker-compose, onde não há servidor de autorização.
 * Em produção {@code enabled=false} e os tokens vêm do IdP (validados via JWKS do issuer-uri).
 */
@ConfigurationProperties("app.local-dev")
public record LocalDevProperties(
        @DefaultValue("false") boolean enabled,
        String jwtSecret,
        @DefaultValue("http://localhost/local-dev") String issuer) {
}
