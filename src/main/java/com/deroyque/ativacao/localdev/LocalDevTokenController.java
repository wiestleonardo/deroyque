package com.deroyque.ativacao.localdev;

import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.constants.SegurancaConstants;
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
@ConditionalOnProperty(name = "local-dev.habilitado", havingValue = "true")
public class LocalDevTokenController {

    private static final Duration VALIDADE_TOKEN = Duration.ofHours(1);
    private static final String ESCOPO_PADRAO = SegurancaConstants.ESCOPO_LEITURA + " " + SegurancaConstants.ESCOPO_ESCRITA;

    private final JwtEncoder jwtEncoder;

    private final LocalDevProperties localDevProperties;

    private final Clock clock;

    @Value("${spring.security.oauth2.resourceserver.jwt.audiences}")
    private final List<String> listaAudiencia;

    @PostMapping(RotaConstants.LocalDev.TOKEN)
    public ResponseEntity<Map<String, Object>> gerarToken(@RequestParam(defaultValue = "local-system") String clientId,
            @RequestParam(defaultValue = ESCOPO_PADRAO) String scope) {

        Instant agora = clock.instant();

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .issuer(localDevProperties.emissor())
                .subject(clientId)
                .audience(listaAudiencia)
                .issuedAt(agora)
                .expiresAt(agora.plus(VALIDADE_TOKEN))
                .claim("client_id", clientId)
                .claim("scope", scope)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), jwtClaimsSet)).getTokenValue();

        return ResponseEntity.ok(Map.of("access_token", token, "token_type", "Bearer", "expires_in", VALIDADE_TOKEN.toSeconds()));
    }

}
