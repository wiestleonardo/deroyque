package com.deroyque.ativacao.intercomm.service.impl;

import com.deroyque.ativacao.config.HttpClientConfig;
import com.deroyque.ativacao.config.ProvedorAtivacaoProperties;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaDefinitiva;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaTemporaria;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.Sucesso;
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

class ProvedorAtivacaoIntercommServiceImplTest {

    private static final Instant AGORA = Instant.parse("2026-10-26T14:00:00Z");
    private static final String ROTA_ATIVAR = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private ProvedorAtivacaoIntercommServiceImpl provedorAtivacaoIntercommServiceImpl;

    private SolicitacaoAtivacao solicitacaoAtivacao;

    @BeforeEach
    void configurar() {

        this.provedorAtivacaoIntercommServiceImpl = criarServico(wireMockExtension.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500), "chave-provedor");
        this.solicitacaoAtivacao = SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-123", AGORA);
    }

    @AfterEach
    void limparCorrelacao() {

        CorrelacaoUtil.limpar();
    }

    @Test
    void deveEnviarChaveIdempotenciaCorrelacaoECredencialQuandoAtivarComSucesso() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(200)));
        CorrelacaoUtil.definir("corr-123");

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao)).isInstanceOf(Sucesso.class);

        wireMockExtension.verify(postRequestedFor(urlEqualTo(ROTA_ATIVAR))
                .withHeader("Idempotency-Key", equalTo(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-Api-Key", equalTo("chave-provedor"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao()))));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void deveSerFalhaDefinitivaQuandoProvedorResponderErroDoCliente(int status) {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(status)));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao)).isEqualTo(new FalhaDefinitiva("PROVIDER_REJECTED_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504, 408})
    void deveSerFalhaTemporariaQuandoProvedorResponderErroDoServidor(int status) {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(status)));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao)).isEqualTo(new FalhaTemporaria("PROVIDER_HTTP_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void deveRespeitarRetryAfterEmSegundosQuandoProvedorPedirEspera(int status) {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(status).withHeader("Retry-After", "17")));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao))
                .isEqualTo(new FalhaTemporaria("PROVIDER_HTTP_" + status, Duration.ofSeconds(17)));
    }

    @Test
    void deveConverterRetryAfterQuandoVierComoDataHttp() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "Mon, 26 Oct 2026 14:01:00 GMT")));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao))
                .isEqualTo(new FalhaTemporaria("PROVIDER_HTTP_503", Duration.ofSeconds(60)));
    }

    @Test
    void deveSerFalhaTemporariaQuandoProvedorRecusarCredencial() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(401)));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao)).isEqualTo(new FalhaTemporaria("PROVIDER_AUTH_401"));
    }

    @Test
    void deveSerTempoLimiteQuandoProvedorResponderDepoisDoTimeout() {

        wireMockExtension.stubFor(post(ROTA_ATIVAR).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThat(this.provedorAtivacaoIntercommServiceImpl.ativar(this.solicitacaoAtivacao)).isEqualTo(new FalhaTemporaria("PROVIDER_TIMEOUT"));
    }

    @Test
    void deveSerFalhaTemporariaQuandoProvedorEstiverInacessivel() {

        ProvedorAtivacaoIntercommServiceImpl provedorInacessivel = criarServico("http://localhost:1", Duration.ofMillis(300), Duration.ofMillis(300), null);

        assertThat(provedorInacessivel.ativar(this.solicitacaoAtivacao))
                .isInstanceOf(FalhaTemporaria.class)
                .extracting("motivo")
                .isIn("PROVIDER_UNREACHABLE", "PROVIDER_TIMEOUT");
    }

    private static ProvedorAtivacaoIntercommServiceImpl criarServico(String urlBase, Duration tempoLimiteConexao, Duration tempoLimiteLeitura,
            String chaveApi) {

        ProvedorAtivacaoProperties provedorAtivacaoProperties = new ProvedorAtivacaoProperties(urlBase, tempoLimiteConexao, tempoLimiteLeitura, chaveApi);

        return new ProvedorAtivacaoIntercommServiceImpl(new HttpClientConfig().provedorAtivacaoClient(RestClient.builder(), provedorAtivacaoProperties),
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }

}
