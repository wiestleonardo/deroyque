package com.deroyque.activation.infrastructure.provider;

import com.deroyque.activation.application.provider.ProviderOutcome.PermanentFailure;
import com.deroyque.activation.application.provider.ProviderOutcome.Success;
import com.deroyque.activation.application.provider.ProviderOutcome.TransientFailure;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.shared.CorrelationId;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Teste do cliente HTTP contra um servidor real (WireMock), sem contexto Spring. */
class HttpActivationProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private HttpActivationProvider provider;
    private ActivationRequest request;

    @BeforeEach
    void setUp() {
        var properties = new ProviderProperties(wireMock.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500),
                "secret-key");
        provider = new HttpActivationProvider(RestClient.builder(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
        request = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-123", NOW);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successSendsIdempotencyKeyCorrelationAndCredential() {
        wireMock.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(200)));
        MDC.put(CorrelationId.MDC_KEY, "corr-123");

        var outcome = provider.activate(request);

        assertThat(outcome).isInstanceOf(Success.class);
        wireMock.verify(postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-Api-Key", equalTo("secret-key"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(request.getId()))));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void clientErrorsArePermanent(int status) {
        wireMock.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(status)));

        assertThat(provider.activate(request)).isEqualTo(new PermanentFailure("PROVIDER_REJECTED_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504, 408})
    void serverErrorsAreTransient(int status) {
        wireMock.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(status)));

        assertThat(provider.activate(request)).isEqualTo(new TransientFailure("PROVIDER_HTTP_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void throttlingHonorsRetryAfterSeconds(int status) {
        wireMock.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(status).withHeader("Retry-After", "17")));

        assertThat(provider.activate(request))
                .isEqualTo(new TransientFailure("PROVIDER_HTTP_" + status, Duration.ofSeconds(17)));
    }

    @Test
    void retryAfterAsHttpDate() {
        wireMock.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "Mon, 26 Oct 2026 14:01:00 GMT")));

        assertThat(provider.activate(request))
                .isEqualTo(new TransientFailure("PROVIDER_HTTP_503", Duration.ofSeconds(60)));
    }

    @Test
    void credentialRejectionIsTransient() {
        wireMock.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(401)));

        assertThat(provider.activate(request)).isEqualTo(new TransientFailure("PROVIDER_AUTH_401"));
    }

    @Test
    void responseAfterReadTimeoutIsTransientTimeout() {
        wireMock.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThat(provider.activate(request)).isEqualTo(new TransientFailure("PROVIDER_TIMEOUT"));
    }

    @Test
    void unreachableProviderIsTransient() {
        var properties = new ProviderProperties("http://localhost:1", Duration.ofMillis(300), Duration.ofMillis(300), null);
        var unreachable = new HttpActivationProvider(RestClient.builder(), properties, Clock.systemUTC());

        assertThat(unreachable.activate(request))
                .isInstanceOf(TransientFailure.class)
                .extracting("reason").isIn("PROVIDER_UNREACHABLE", "PROVIDER_TIMEOUT");
    }
}
