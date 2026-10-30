package com.deroyque.activation.intercomm.service.impl;

import com.deroyque.activation.config.ActivationProviderProperties;
import com.deroyque.activation.config.HttpClientConfig;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.vo.ActivationResultVO.PermanentFailure;
import com.deroyque.activation.vo.ActivationResultVO.Success;
import com.deroyque.activation.vo.ActivationResultVO.TransientFailure;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class ActivationProviderIntercommServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final String ACTIVATE_PATH = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private ActivationProviderIntercommServiceImpl activationProviderIntercommServiceImpl;

    private ActivationRequest activationRequest;

    @BeforeEach
    void setUp() {

        this.activationProviderIntercommServiceImpl = createService(wireMockExtension.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500),
                "provider-key");
        this.activationRequest = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-123", NOW);
    }

    @AfterEach
    void clearCorrelation() {

        CorrelationUtil.clear();
    }

    @Test
    void shouldSendIdempotencyKeyCorrelationAndCredentialWhenActivationSucceeds() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
        CorrelationUtil.set("corr-123");

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest)).isInstanceOf(Success.class);

        wireMockExtension.verify(postRequestedFor(urlEqualTo(ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(this.activationRequest.getId().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-Api-Key", equalTo("provider-key"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(this.activationRequest.getId()))));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void shouldBePermanentFailureWhenProviderReturnsClientError(int status) {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(status)));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest))
                .isEqualTo(new PermanentFailure("PROVIDER_REJECTED_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504, 408})
    void shouldBeTransientFailureWhenProviderReturnsServerError(int status) {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(status)));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest)).isEqualTo(new TransientFailure("PROVIDER_HTTP_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void shouldHonorRetryAfterInSecondsWhenProviderAsksToWait(int status) {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(status).withHeader("Retry-After", "17")));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest))
                .isEqualTo(new TransientFailure("PROVIDER_HTTP_" + status, Duration.ofSeconds(17)));
    }

    @Test
    void shouldParseRetryAfterWhenItIsAnHttpDate() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "Mon, 26 Oct 2026 14:01:00 GMT")));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest))
                .isEqualTo(new TransientFailure("PROVIDER_HTTP_503", Duration.ofSeconds(60)));
    }

    @Test
    void shouldBeTransientFailureWhenProviderRejectsCredential() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(401)));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest)).isEqualTo(new TransientFailure("PROVIDER_AUTH_401"));
    }

    @Test
    void shouldBeTimeoutWhenProviderAnswersAfterReadTimeout() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThat(this.activationProviderIntercommServiceImpl.activate(this.activationRequest)).isEqualTo(new TransientFailure("PROVIDER_TIMEOUT"));
    }

    @Test
    void shouldBeTransientFailureWhenProviderIsUnreachable() {

        ActivationProviderIntercommServiceImpl unreachableProvider = createService("http://localhost:1", Duration.ofMillis(300), Duration.ofMillis(300), null);

        assertThat(unreachableProvider.activate(this.activationRequest))
                .isInstanceOf(TransientFailure.class)
                .extracting("reason")
                .isIn("PROVIDER_UNREACHABLE", "PROVIDER_TIMEOUT");
    }

    private static ActivationProviderIntercommServiceImpl createService(String baseUrl, Duration connectTimeout, Duration readTimeout, String apiKey) {

        ActivationProviderProperties activationProviderProperties = new ActivationProviderProperties(baseUrl, connectTimeout, readTimeout, apiKey);

        return new ActivationProviderIntercommServiceImpl(new HttpClientConfig().activationProviderClient(RestClient.builder(), activationProviderProperties),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

}
