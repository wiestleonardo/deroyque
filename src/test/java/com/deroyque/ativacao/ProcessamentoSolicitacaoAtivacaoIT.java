package com.deroyque.ativacao;

import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.support.IntegrationTest;
import com.deroyque.ativacao.support.TokenTeste;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
class ProcessamentoSolicitacaoAtivacaoIT {

    private static final String ROTA_PROVEDOR = "/provider/v1/activate";
    private static final String ROTA_ADQUIRIR = RotaConstants.SolicitacaoAtivacao.BASE + RotaConstants.SolicitacaoAtivacao.ADQUIRIR;

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void configurarPropriedades(DynamicPropertyRegistry dynamicPropertyRegistry) {

        dynamicPropertyRegistry.add("intercomm.provedor-ativacao.url-base", wireMockExtension::baseUrl);
        dynamicPropertyRegistry.add("intercomm.provedor-ativacao.tempo-limite-leitura", () -> "1s");
        dynamicPropertyRegistry.add("processamento.job.habilitado", () -> "true");
        dynamicPropertyRegistry.add("processamento.job.intervalo", () -> "100ms");
        dynamicPropertyRegistry.add("processamento.quantidade-maxima-tentativa", () -> "3");
        dynamicPropertyRegistry.add("processamento.espera-inicial", () -> "100ms");
        dynamicPropertyRegistry.add("processamento.espera-maxima", () -> "200ms");
    }

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void limpar() {

        solicitacaoAtivacaoRepository.deleteAllInBatch();
        wireMockExtension.resetAll();
    }

    @Test
    void deveConcluirEPropagarCorrelacaoQuandoProvedorAtivar() {

        wireMockExtension.stubFor(post(ROTA_PROVEDOR).willReturn(aResponse().withStatus(200)));

        String idSolicitacao = this.criar("corr-fluxo-1");

        this.aguardarStatus(idSolicitacao, "COMPLETED");
        wireMockExtension.verify(1, postRequestedFor(urlEqualTo(ROTA_PROVEDOR))
                .withHeader("Idempotency-Key", equalTo(idSolicitacao))
                .withHeader("X-Correlation-Id", equalTo("corr-fluxo-1")));
    }

    @Test
    void deveFalharSemNovaTentativaQuandoProvedorRecusar() {

        wireMockExtension.stubFor(post(ROTA_PROVEDOR).willReturn(aResponse().withStatus(400)));

        String idSolicitacao = this.criar("corr-fluxo-2");

        this.aguardarStatus(idSolicitacao, "FAILED");
        MvcTestResult mvcTestResult = mockMvcTester.get().uri(ROTA_ADQUIRIR, idSolicitacao).with(TokenTeste.leitura()).exchange();
        assertThat(mvcTestResult).bodyJson().extractingPath("$.failureReason").isEqualTo("PROVIDER_REJECTED_400");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.attempts").isEqualTo(1);
    }

    @Test
    void deveFalharDepoisDoLimiteQuandoProvedorResponderErroSempre() {

        wireMockExtension.stubFor(post(ROTA_PROVEDOR).willReturn(aResponse().withStatus(500)));

        String idSolicitacao = this.criar("corr-fluxo-3");

        this.aguardarStatus(idSolicitacao, "FAILED");
        MvcTestResult mvcTestResult = mockMvcTester.get().uri(ROTA_ADQUIRIR, idSolicitacao).with(TokenTeste.leitura()).exchange();
        assertThat(mvcTestResult).bodyJson().extractingPath("$.failureReason").isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_HTTP_500");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.attempts").isEqualTo(3);
        wireMockExtension.verify(3, postRequestedFor(urlEqualTo(ROTA_PROVEDOR)));
    }

    @Test
    void deveRepetirComMesmaChaveEConcluirQuandoPrimeiraChamadaEstourarTimeout() {

        wireMockExtension.stubFor(post(ROTA_PROVEDOR).inScenario("lento-depois-ok").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(2500))
                .willSetStateTo("recuperado"));
        wireMockExtension.stubFor(post(ROTA_PROVEDOR).inScenario("lento-depois-ok").whenScenarioStateIs("recuperado")
                .willReturn(aResponse().withStatus(200)));

        String idSolicitacao = this.criar("corr-fluxo-4");

        this.aguardarStatus(idSolicitacao, "COMPLETED");
        wireMockExtension.verify(2, postRequestedFor(urlEqualTo(ROTA_PROVEDOR)).withHeader("Idempotency-Key", equalTo(idSolicitacao)));
    }

    private String criar(String codigoCorrelacao) {

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .header("X-Correlation-Id", codigoCorrelacao)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT-%s","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(UUID.randomUUID()))
                .exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);

        return JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");
    }

    private void aguardarStatus(String idSolicitacao, String statusEsperado) {

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(mockMvcTester.get().uri(ROTA_ADQUIRIR, idSolicitacao).with(TokenTeste.leitura()))
                        .hasStatusOk()
                        .bodyJson().extractingPath("$.status").isEqualTo(statusEsperado));
    }

}
