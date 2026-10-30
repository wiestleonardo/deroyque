package com.deroyque.activation.localdev;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
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
import java.security.SecureRandom;
import java.util.List;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "local-dev.enabled", havingValue = "true")
public class LocalDevSecurityConfig {

    private static final Integer MIN_SECRET_BYTES = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AUDIENCE_CLAIM = "aud";

    @Bean
    public SecretKey localDevKey(LocalDevProperties localDevProperties) {

        log.warn("Local-dev mode enabled: test token issuer is active");

        if (localDevProperties.jwtSecret() == null || localDevProperties.jwtSecret().isBlank()) {

            byte[] randomSecret = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(randomSecret);

            return new SecretKeySpec(randomSecret, HMAC_ALGORITHM);
        }

        byte[] secret = localDevProperties.jwtSecret().getBytes(StandardCharsets.UTF_8);

        if (secret.length < MIN_SECRET_BYTES) {

            throw new IllegalStateException("local-dev.jwt-secret must have at least 32 bytes");
        }

        return new SecretKeySpec(secret, HMAC_ALGORITHM);
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey localDevKey, LocalDevProperties localDevProperties,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> audienceList) {

        NimbusJwtDecoder nimbusJwtDecoder = NimbusJwtDecoder.withSecretKey(localDevKey).macAlgorithm(MacAlgorithm.HS256).build();

        JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(AUDIENCE_CLAIM,
                audience -> audience != null && audience.stream().anyMatch(audienceList::contains));

        nimbusJwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(localDevProperties.issuer()),
                audienceValidator));

        return nimbusJwtDecoder;
    }

    @Bean
    public JwtEncoder localDevJwtEncoder(SecretKey localDevKey) {

        return new NimbusJwtEncoder(new ImmutableSecret<>(localDevKey));
    }

}
