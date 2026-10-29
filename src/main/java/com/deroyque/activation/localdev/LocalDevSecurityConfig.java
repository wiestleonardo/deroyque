package com.deroyque.activation.localdev;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Substitui o IdP no ambiente local: tokens HS256 assinados com um segredo compartilhado.
 * O decoder aplica as mesmas validações de produção: assinatura, expiração, emissor e audiência.
 */
@Configuration
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
public class LocalDevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalDevSecurityConfig.class);

    @Bean
    SecretKey localDevJwtKey(LocalDevProperties properties) {
        var secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.local-dev.jwt-secret precisa ter ao menos 32 bytes");
        }
        log.warn("Modo local-dev ATIVO: tokens de teste e provedor simulado habilitados. Não use em produção.");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey localDevJwtKey, LocalDevProperties properties,
                          @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> audiences) {
        var decoder = NimbusJwtDecoder.withSecretKey(localDevJwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.stream().anyMatch(audiences::contains));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audienceValidator));
        return decoder;
    }

    @Bean
    JwtEncoder localDevJwtEncoder(SecretKey localDevJwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(localDevJwtKey));
    }
}
