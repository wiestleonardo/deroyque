package com.deroyque.activation;

import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.support.IntegrationTest;
import com.deroyque.activation.support.TestTokens;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
class ActivationProcessingIT {

    private static final String PROVIDER_PATH = "/provider/v1/activate";
    private static final String GET_BY_ID_PATH = RouteConstants.ActivationRequest.BASE + RouteConstants.ActivationRequest.GET_BY_ID;

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry dynamicPropertyRegistry) {

        dynamicPropertyRegistry.add("intercomm.activation-provider.base-url", wireMockExtension::baseUrl);
        dynamicPropertyRegistry.add("intercomm.activation-provider.read-timeout", () -> "500ms");
        dynamicPropertyRegistry.add("processing.job.enabled", () -> "true");
        dynamicPropertyRegistry.add("processing.job.interval", () -> "100ms");
        dynamicPropertyRegistry.add("processing.max-attempts", () -> "3");
        dynamicPropertyRegistry.add("processing.initial-backoff", () -> "100ms");
        dynamicPropertyRegistry.add("processing.max-backoff", () -> "200ms");
    }

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private ActivationRequestRepository activationRequestRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {

        activationRequestRepository.deleteAllInBatch();
        wireMockExtension.resetAll();
    }

    @Test
    void shouldCompleteAndPropagateCorrelationWhenProviderActivates() {

        wireMockExtension.stubFor(post(PROVIDER_PATH).willReturn(aResponse().withStatus(200)));

        String requestId = this.create("corr-flow-1");

        this.awaitStatus(requestId, "COMPLETED");
        wireMockExtension.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH))
                .withHeader("Idempotency-Key", equalTo(requestId))
                .withHeader("X-Correlation-Id", equalTo("corr-flow-1")));
    }

    @Test
    void shouldFailWithoutRetryWhenProviderRejects() {

        wireMockExtension.stubFor(post(PROVIDER_PATH).willReturn(aResponse().withStatus(400)));

        String requestId = this.create("corr-flow-2");

        this.awaitStatus(requestId, "FAILED");
        MvcTestResult mvcTestResult = mockMvcTester.get().uri(GET_BY_ID_PATH, requestId).with(TestTokens.reader()).exchange();
        assertThat(mvcTestResult).bodyJson().extractingPath("$.failureReason").isEqualTo("PROVIDER_REJECTED_400");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.attempts").isEqualTo(1);
        wireMockExtension.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
    }

    @Test
    void shouldFailAfterMaxAttemptsWhenProviderKeepsReturningServerError() {

        wireMockExtension.stubFor(post(PROVIDER_PATH).willReturn(aResponse().withStatus(500)));

        String requestId = this.create("corr-flow-3");

        this.awaitStatus(requestId, "FAILED");
        MvcTestResult mvcTestResult = mockMvcTester.get().uri(GET_BY_ID_PATH, requestId).with(TestTokens.reader()).exchange();
        assertThat(mvcTestResult).bodyJson().extractingPath("$.failureReason").isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_HTTP_500");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.attempts").isEqualTo(3);
        wireMockExtension.verify(3, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
    }

    @Test
    void shouldRetryWithSameKeyAndCompleteWhenFirstCallTimesOut() {

        wireMockExtension.stubFor(post(PROVIDER_PATH).inScenario("slow-then-ok").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500))
                .willSetStateTo("recovered"));
        wireMockExtension.stubFor(post(PROVIDER_PATH).inScenario("slow-then-ok").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        String requestId = this.create("corr-flow-4");

        this.awaitStatus(requestId, "COMPLETED");
        wireMockExtension.verify(2, postRequestedFor(urlEqualTo(PROVIDER_PATH))
                .withHeader("Idempotency-Key", equalTo(requestId))
                .withRequestBody(matchingJsonPath("$.requestId", equalTo(requestId))));
    }

    private String create(String correlationId) {

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT-%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(UUID.randomUUID()))
                .exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);

        return JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");
    }

    private void awaitStatus(String requestId, String expectedStatus) {

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(mockMvcTester.get().uri(GET_BY_ID_PATH, requestId).with(TestTokens.reader()))
                        .hasStatusOk()
                        .bodyJson().extractingPath("$.status").isEqualTo(expectedStatus));
    }

}
