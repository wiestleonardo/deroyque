package com.deroyque.activation;

import com.deroyque.activation.localdev.LocalDevProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validação real de JWT (assinatura, emissor, audiência, expiração) com o decoder do modo local-dev,
 * sem servidor de autenticação, e isolamento entre consumidores (BOLA).
 */
@SpringBootTest(properties = {
        "app.local-dev.enabled=true",
        "app.local-dev.jwt-secret=test-secret-with-at-least-32-bytes-0000"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SecurityAndIsolationIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private LocalDevProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void consumerCannotSeeRequestsOfAnotherConsumer() throws Exception {
        var tokenA = token("system-a", "activation:read activation:write", "activation-service", properties.issuer(),
                Instant.now().plusSeconds(300));
        var tokenB = token("system-b", "activation:read activation:write", "activation-service", properties.issuer(),
                Instant.now().plusSeconds(300));

        var body = mvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = objectMapper.readTree(body).get("id").asText();

        mvc.perform(get("/api/v1/requests/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/requests/{id}", id).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(id)).isEmpty());
    }

    @Test
    void tokenFromDevEndpointIsAccepted() throws Exception {
        var response = mvc.perform(post("/dev/token").param("clientId", "system-c").param("scope", "activation:read"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var token = objectMapper.readTree(response).get("access_token").asText();

        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithWrongAudienceIsRejected() throws Exception {
        var token = token("system-a", "activation:read", "other-service", properties.issuer(),
                Instant.now().plusSeconds(300));

        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithWrongIssuerIsRejected() throws Exception {
        var token = token("system-a", "activation:read", "activation-service", "https://evil.example",
                Instant.now().plusSeconds(300));

        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        var token = token("system-a", "activation:read", "activation-service", properties.issuer(),
                Instant.now().minusSeconds(600));

        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        var token = token("system-a", "activation:read", "activation-service", properties.issuer(),
                Instant.now().plusSeconds(300));
        var tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndMetricsArePublicAndExposeActivationMetrics() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("activation_requests{")));
    }

    private String token(String clientId, String scope, String audience, String issuer, Instant expiresAt) {
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(clientId)
                .audience(List.of(audience))
                .issuedAt(expiresAt.minusSeconds(3600))
                .expiresAt(expiresAt)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
