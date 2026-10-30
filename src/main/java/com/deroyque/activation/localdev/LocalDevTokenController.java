package com.deroyque.activation.localdev;

import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.constants.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "local-dev.enabled", havingValue = "true")
public class LocalDevTokenController {

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    private static final String DEFAULT_SCOPE = SecurityConstants.SCOPE_READ + " " + SecurityConstants.SCOPE_WRITE;

    private final JwtEncoder jwtEncoder;

    private final LocalDevProperties localDevProperties;

    private final Clock clock;

    @Value("${spring.security.oauth2.resourceserver.jwt.audiences}")
    private final List<String> audienceList;

    @PostMapping(RouteConstants.LocalDev.TOKEN)
    public ResponseEntity<Map<String, Object>> generateToken(@RequestParam(defaultValue = "local-system") String clientId,
            @RequestParam(defaultValue = DEFAULT_SCOPE) String scope) {

        Instant now = clock.instant();

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .issuer(localDevProperties.issuer())
                .subject(clientId)
                .audience(audienceList)
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_VALIDITY))
                .claim("client_id", clientId)
                .claim("scope", scope)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), jwtClaimsSet)).getTokenValue();

        return ResponseEntity.ok(Map.of("access_token", token, "token_type", "Bearer", "expires_in", TOKEN_VALIDITY.toSeconds()));
    }

}
