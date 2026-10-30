package com.deroyque.activation.intercomm.service.impl;

import com.deroyque.activation.config.ActivationProviderProperties;
import com.deroyque.activation.config.HttpClientConfig;
import com.deroyque.activation.config.IntegrationErrorHandler;
import com.deroyque.activation.exception.IntegrationException;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.util.CorrelationUtil;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationProviderIntercommServiceImplTest {

    private static final String ACTIVATE_PATH = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private ActivationProviderIntercommServiceImpl activationProviderIntercommServiceImpl;

    private ActivationRequest activationRequest;

    @BeforeEach
    void setUp() {

        ActivationProviderProperties activationProviderProperties = new ActivationProviderProperties(wireMockExtension.baseUrl(), Duration.ofSeconds(1),
                Duration.ofMillis(500), "provider-key");

        this.activationProviderIntercommServiceImpl = new ActivationProviderIntercommServiceImpl(
                new HttpClientConfig().activationProviderClient(RestClient.builder(), new IntegrationErrorHandler(), activationProviderProperties));
        this.activationRequest = ActivationRequest.create("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-123", Instant.now());
    }

    @AfterEach
    void clearCorrelation() {

        CorrelationUtil.clear();
    }

    @Test
    void shouldSendIdempotencyKeyCorrelationAndCredentialWhenActivating() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
        CorrelationUtil.set("corr-123");

        this.activationProviderIntercommServiceImpl.activate(this.activationRequest);

        wireMockExtension.verify(postRequestedFor(urlEqualTo(ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(this.activationRequest.getId().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-Api-Key", equalTo("provider-key"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(this.activationRequest.getId()))));
    }

    @Test
    void shouldNotRetryWhenProviderReturns400() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(400)));

        this.assertFailure("PROVIDER_REJECTED_400", false, null);
    }

    @Test
    void shouldRetryWhenProviderReturns500() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(500)));

        this.assertFailure("PROVIDER_HTTP_500", true, null);
    }

    @Test
    void shouldNotTreatRedirectAsSuccess() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));

        this.assertFailure("PROVIDER_REJECTED_302", false, null);
    }

    @Test
    void shouldRetryWhenProviderRejectsOurCredential() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(401)));

        this.assertFailure("PROVIDER_HTTP_401", true, null);
    }

    @Test
    void shouldReadRetryAfterWhenProviderReturns503() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "17")));

        this.assertFailure("PROVIDER_HTTP_503", true, Duration.ofSeconds(17));
    }

    @Test
    void shouldRetryWhenProviderAnswersAfterReadTimeout() {

        wireMockExtension.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        this.assertFailure("PROVIDER_TIMEOUT", true, null);
    }

    private void assertFailure(String reason, Boolean retryable, Duration retryAfter) {

        assertThatThrownBy(() -> this.activationProviderIntercommServiceImpl.activate(this.activationRequest))
                .isInstanceOfSatisfying(IntegrationException.class, integrationException -> {

                    assertThat(integrationException.getReason()).isEqualTo(reason);
                    assertThat(integrationException.getRetryable()).isEqualTo(retryable);
                    assertThat(integrationException.getRetryAfter()).isEqualTo(retryAfter);
                });
    }

}
