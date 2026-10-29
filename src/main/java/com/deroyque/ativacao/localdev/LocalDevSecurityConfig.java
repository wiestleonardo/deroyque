package com.deroyque.ativacao.localdev;

import com.deroyque.ativacao.util.ObjectUtil;
import com.deroyque.ativacao.util.StringUtil;
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
@ConditionalOnProperty(name = "local-dev.habilitado", havingValue = "true")
public class LocalDevSecurityConfig {

    private static final Integer TAMANHO_MINIMO_SEGREDO_BYTES = 32;
    private static final String ALGORITMO_HMAC = "HmacSHA256";
    private static final String CLAIM_AUDIENCIA = "aud";

    @Bean
    public SecretKey chaveLocalDev(LocalDevProperties localDevProperties) {

        log.warn("Modo local-dev ativo: emissor de tokens de teste e provedor simulado habilitados");

        if (StringUtil.isNullOrEmpty(localDevProperties.segredoJwt())) {

            byte[] segredoAleatorio = new byte[TAMANHO_MINIMO_SEGREDO_BYTES];
            new SecureRandom().nextBytes(segredoAleatorio);

            return new SecretKeySpec(segredoAleatorio, ALGORITMO_HMAC);
        }

        byte[] segredo = localDevProperties.segredoJwt().getBytes(StandardCharsets.UTF_8);

        if (segredo.length < TAMANHO_MINIMO_SEGREDO_BYTES) {

            throw new IllegalStateException("local-dev.segredo-jwt precisa ter ao menos 32 bytes");
        }

        return new SecretKeySpec(segredo, ALGORITMO_HMAC);
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey chaveLocalDev, LocalDevProperties localDevProperties,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> listaAudiencia) {

        NimbusJwtDecoder nimbusJwtDecoder = NimbusJwtDecoder.withSecretKey(chaveLocalDev).macAlgorithm(MacAlgorithm.HS256).build();

        JwtClaimValidator<List<String>> validadorAudiencia = new JwtClaimValidator<>(CLAIM_AUDIENCIA,
                audiencia -> ObjectUtil.isNotNull(audiencia) && audiencia.stream().anyMatch(listaAudiencia::contains));

        nimbusJwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(localDevProperties.emissor()),
                validadorAudiencia));

        return nimbusJwtDecoder;
    }

    @Bean
    public JwtEncoder jwtEncoderLocalDev(SecretKey chaveLocalDev) {

        return new NimbusJwtEncoder(new ImmutableSecret<>(chaveLocalDev));
    }

}
