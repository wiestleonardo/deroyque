package com.deroyque.activation;

import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.localdev.LocalDevProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureObservability
@SpringBootTest(properties = "local-dev.enabled=true")
class SecurityIsolationIT {

    private static final String FULL_SCOPE = "activation:read activation:write";
    private static final String AUDIENCE = "activation-service";
    private static final String GET_BY_ID_PATH = RouteConstants.ActivationRequest.BASE + RouteConstants.ActivationRequest.GET_BY_ID;

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private LocalDevProperties localDevProperties;

    @Test
    void shouldHideRequestWhenAnotherSystemQueriesIt() {

        String systemAToken = this.generateToken("system-a", FULL_SCOPE, AUDIENCE, this.localDevProperties.issuer(), Instant.now().plusSeconds(300));
        String systemBToken = this.generateToken("system-b", FULL_SCOPE, AUDIENCE, this.localDevProperties.issuer(), Instant.now().plusSeconds(300));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT-%s","customerId":"CUST-ISOLATED","productCode":"INTERNET-500"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);
        String requestId = JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");

        assertThat(this.mockMvcTester.get().uri(GET_BY_ID_PATH, requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + systemAToken))
                .hasStatusOk();
        assertThat(this.mockMvcTester.get().uri(GET_BY_ID_PATH, requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + systemBToken))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(this.mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).param("customerId", "CUST-ISOLATED").param("status", "PENDING")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemAToken))
                .hasStatusOk()
                .bodyJson().extractingPath("$.content[*].id").asArray().containsExactly(requestId);
        assertThat(this.mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).param("customerId", "CUST-ISOLATED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemBToken))
                .hasStatusOk()
                .bodyJson().extractingPath("$.content").asArray().isEmpty();
    }

    @Test
    void shouldReturnBadRequestWhenStatusFilterDoesNotExist() {

        String token = this.generateToken("system-a", FULL_SCOPE, AUDIENCE, this.localDevProperties.issuer(), Instant.now().plusSeconds(300));

        assertThat(this.mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).param("status", "DONE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void shouldAcceptTokenWhenIssuedByLocalEndpoint() {

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RouteConstants.LocalDev.TOKEN).param("clientId", "system-c")
                .param("scope", "activation:read").exchange();
        assertThat(mvcTestResult).hasStatusOk();
        String token = JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.access_token");

        assertThat(this.mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk();
    }

    @Test
    void shouldRejectWhenAudienceIsDifferent() {

        String token = this.generateToken("system-a", FULL_SCOPE, "other-service", this.localDevProperties.issuer(), Instant.now().plusSeconds(300));

        this.assertUnauthorized(token);
    }

    @Test
    void shouldRejectWhenIssuerIsDifferent() {

        String token = this.generateToken("system-a", FULL_SCOPE, AUDIENCE, "https://evil.example", Instant.now().plusSeconds(300));

        this.assertUnauthorized(token);
    }

    @Test
    void shouldRejectWhenTokenIsExpired() {

        String token = this.generateToken("system-a", FULL_SCOPE, AUDIENCE, this.localDevProperties.issuer(), Instant.now().minusSeconds(600));

        this.assertUnauthorized(token);
    }

    @Test
    void shouldRejectWhenSignatureIsTampered() {

        String token = this.generateToken("system-a", FULL_SCOPE, AUDIENCE, this.localDevProperties.issuer(), Instant.now().plusSeconds(300));

        this.assertUnauthorized(token.substring(0, token.length() - 4) + "AAAA");
    }

    @Test
    void shouldExposeHealthAndMetricsWithoutTokenWhenEndpointIsActuator() {

        assertThat(this.mockMvcTester.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(this.mockMvcTester.get().uri("/actuator/health/readiness")).hasStatusOk();
        assertThat(this.mockMvcTester.get().uri("/actuator/prometheus")).hasStatusOk().bodyText().contains("activation_requests{");
    }

    private void assertUnauthorized(String token) {

        assertThat(this.mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private String generateToken(String clientId, String scope, String audience, String issuer, Instant expiresAt) {

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(clientId)
                .audience(List.of(audience))
                .issuedAt(expiresAt.minusSeconds(3600))
                .expiresAt(expiresAt)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .build();

        return this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), jwtClaimsSet)).getTokenValue();
    }

}
