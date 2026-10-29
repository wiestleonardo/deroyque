package com.deroyque.activation;

import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.infrastructure.security.SecurityConfig;
import com.deroyque.activation.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo: API → banco → worker → provedor (WireMock) → status final.
 * Timeout de leitura de 500 ms e backoff curto para o teste ser rápido.
 */
@IntegrationTest
class ActivationFlowIT {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", provider::baseUrl);
        registry.add("app.provider.read-timeout", () -> "500ms");
        registry.add("app.worker.enabled", () -> "true");
        registry.add("app.worker.poll-interval", () -> "100ms");
        registry.add("app.processing.max-attempts", () -> "3");
        registry.add("app.processing.initial-backoff", () -> "100ms");
        registry.add("app.processing.max-backoff", () -> "200ms");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivationRequestRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        provider.resetAll();
    }

    @Test
    void successfulActivationEndsCompleted() throws Exception {
        provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(200)));

        var id = create("INTERNET-500", "corr-flow-1");

        awaitStatus(id, "COMPLETED");
        provider.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-flow-1")));
    }

    @Test
    void rejectionByProviderEndsFailedWithoutRetry() throws Exception {
        provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(400)));

        var id = create("INTERNET-500", null);

        awaitStatus(id, "FAILED");
        mvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                .andExpect(jsonPath("$.failureReason").value("PROVIDER_REJECTED_400"))
                .andExpect(jsonPath("$.attempts").value(1));
        provider.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate")));
    }

    @Test
    void persistentServerErrorRetriesUntilMaxAttemptsThenFails() throws Exception {
        provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(500)));

        var id = create("INTERNET-500", null);

        awaitStatus(id, "FAILED");
        mvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                .andExpect(jsonPath("$.failureReason").value("MAX_ATTEMPTS_EXCEEDED:PROVIDER_HTTP_500"))
                .andExpect(jsonPath("$.attempts").value(3));
        provider.verify(3, postRequestedFor(urlEqualTo("/provider/v1/activate")));
    }

    @Test
    void timeoutIsRetriedWithSameIdempotencyKeyAndCompletes() throws Exception {
        provider.stubFor(post("/provider/v1/activate").inScenario("slow-then-ok").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500))
                .willSetStateTo("recovered"));
        provider.stubFor(post("/provider/v1/activate").inScenario("slow-then-ok").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        var id = create("INTERNET-500", null);

        awaitStatus(id, "COMPLETED");
        provider.verify(2, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withRequestBody(matchingJsonPath("$.requestId", equalTo(id.toString()))));
    }

    private UUID create(String productCode, String correlationId) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/v1/requests").with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"%s","customerId":"CUST-1","productCode":"%s"}
                        """.formatted("EXT-" + UUID.randomUUID(), productCode));
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        var body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                mvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    private static RequestPostProcessor writer() {
        return jwt().jwt(j -> j.claim("client_id", "system-a"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_WRITE));
    }

    private static RequestPostProcessor reader() {
        return jwt().jwt(j -> j.claim("client_id", "system-a"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_READ));
    }
}
