package com.deroyque.ativacao.intercomm.service.impl;

import com.deroyque.ativacao.config.HttpClientConfig;
import com.deroyque.ativacao.config.IntegracaoErrorHandler;
import com.deroyque.ativacao.config.ProvedorAtivacaoProperties;
import com.deroyque.ativacao.exception.IntegracaoException;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.util.CorrelacaoUtil;
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

class ProvedorAtivacaoIntercommServiceImplTest {

    private static final String ROTA_ATIVAR = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private ProvedorAtivacaoIntercommServiceImpl provedorAtivacaoIntercommServiceImpl;

    private SolicitacaoAtivacao solicitacaoAtivacao;

    @BeforeEach
    void configurar() {

        ProvedorAtivacaoProperties provedorAtivacaoProperties = new ProvedorAtivacaoProperties(wireMockExtension.baseUrl(), Duration.ofSeconds(1),
                Duration.ofSeconds(1), "chave-provedor");

        this.provedorAtivacaoIntercommServiceImpl = new ProvedorAtivacaoIntercommServiceImpl(
                new HttpClientConfig().provedorAtivacaoClient(RestClient.builder(), new IntegracaoErrorHandler(), provedorAtivacaoProperties));
        this.solicitacaoAtivacao = SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-123", Instant.now());
    }

    @AfterEach
    void limparCorrelacao() {

        CorrelacaoUtil.limpar();
    }

    @Test
    void deveEnviarChaveIdempotenciaCorrelacaoECredencialQuandoAtivar() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(200)));
        CorrelacaoUtil.definir("corr-123");

        this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao);

        wireMockExtension.verify(postRequestedFor(urlEqualTo(ROTA_ATIVAR))
                .withHeader("Idempotency-Key", equalTo(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-Api-Key", equalTo("chave-provedor"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao()))));
    }

    @Test
    void deveSerFalhaDefinitivaQuandoProvedorResponder400() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(400)));

        this.assertFalha("PROVIDER_REJECTED_400", false, null);
    }

    @Test
    void deveSerFalhaDefinitivaQuandoProvedorRedirecionar() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(302).withHeader("Location", "/outro")));

        this.assertFalha("PROVIDER_REJECTED_302", false, null);
    }

    @Test
    void devePermitirNovaTentativaQuandoProvedorResponder500() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(500)));

        this.assertFalha("PROVIDER_HTTP_500", true, null);
    }

    @Test
    void devePermitirNovaTentativaQuandoProvedorRecusarCredencial() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(401)));

        this.assertFalha("PROVIDER_HTTP_401", true, null);
    }

    @Test
    void deveLerRetryAfterQuandoProvedorResponder503() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "17")));

        this.assertFalha("PROVIDER_HTTP_503", true, Duration.ofSeconds(17));
    }

    @Test
    void devePermitirNovaTentativaQuandoProvedorResponderDepoisDoTimeout() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(200).withFixedDelay(2500)));

        this.assertFalha("PROVIDER_TIMEOUT", true, null);
    }

    private void assertFalha(String motivo, Boolean permiteNovaTentativa, Duration esperaSolicitada) {

        assertThatThrownBy(() -> this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao))
                .isInstanceOfSatisfying(IntegracaoException.class, integracaoException -> {

                    assertThat(integracaoException.getMotivo()).isEqualTo(motivo);
                    assertThat(integracaoException.getPermiteNovaTentativa()).isEqualTo(permiteNovaTentativa);
                    assertThat(integracaoException.getEsperaSolicitada()).isEqualTo(esperaSolicitada);
                });
    }

}
