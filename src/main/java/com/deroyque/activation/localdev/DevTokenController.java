package com.deroyque.activation.localdev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Emite tokens de teste no formato que um IdP emitiria no fluxo client_credentials.
 * Existe apenas com {@code app.local-dev.enabled=true}.
 */
@RestController
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
public class DevTokenController {

    private static final Duration TTL = Duration.ofHours(1);

    private final JwtEncoder encoder;
    private final LocalDevProperties properties;
    private final List<String> audiences;
    private final Clock clock;

    public DevTokenController(JwtEncoder encoder, LocalDevProperties properties,
                              @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> audiences,
                              Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.audiences = audiences;
        this.clock = clock;
    }

    @PostMapping("/dev/token")
    public Map<String, Object> token(@RequestParam(defaultValue = "local-system") String clientId,
                                     @RequestParam(defaultValue = "activation:read activation:write") String scope) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(clientId)
                .audience(audiences)
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .claim("client_id", clientId)
                .claim("scope", scope)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Map.of("access_token", token, "token_type", "Bearer", "expires_in", TTL.toSeconds());
    }
}
