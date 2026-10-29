package com.deroyque.ativacao;

import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.localdev.LocalDevProperties;
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
@SpringBootTest(properties = "local-dev.habilitado=true")
class SegurancaIsolamentoIT {

    private static final String ESCOPO_COMPLETO = "activation:read activation:write";
    private static final String AUDIENCIA = "activation-service";
    private static final String ROTA_ADQUIRIR = RotaConstants.SolicitacaoAtivacao.BASE + "/{id}";

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private LocalDevProperties localDevProperties;

    @Test
    void deveEsconderSolicitacaoQuandoOutroSistemaConsultar() {

        String tokenSistemaA = this.gerarToken("system-a", ESCOPO_COMPLETO, AUDIENCIA, this.localDevProperties.emissor(), Instant.now().plusSeconds(300));
        String tokenSistemaB = this.gerarToken("system-b", ESCOPO_COMPLETO, AUDIENCIA, this.localDevProperties.emissor(), Instant.now().plusSeconds(300));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSistemaA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT-%s","customerId":"CUST-ISOLADO","productCode":"INTERNET-500"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);
        String idSolicitacao = JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");

        assertThat(this.mockMvcTester.get().uri(ROTA_ADQUIRIR, idSolicitacao).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSistemaA))
                .hasStatusOk();
        assertThat(this.mockMvcTester.get().uri(ROTA_ADQUIRIR, idSolicitacao).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSistemaB))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).param("customerId", "CUST-ISOLADO").param("status", "PENDING")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSistemaA))
                .hasStatusOk()
                .bodyJson().extractingPath("$.content[*].id").asArray().containsExactly(idSolicitacao);
        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).param("customerId", "CUST-ISOLADO")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSistemaB))
                .hasStatusOk()
                .bodyJson().extractingPath("$.content").asArray().isEmpty();
    }

    @Test
    void deveResponderBadRequestQuandoStatusDoFiltroNaoExistir() {

        String token = this.gerarToken("system-a", ESCOPO_COMPLETO, AUDIENCIA, this.localDevProperties.emissor(), Instant.now().plusSeconds(300));

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).param("status", "DONE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void deveAceitarTokenQuandoGeradoPeloEndpointLocal() {

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.LocalDev.TOKEN).param("clientId", "system-c")
                .param("scope", "activation:read").exchange();
        assertThat(mvcTestResult).hasStatusOk();
        String token = JsonPath.read(new String(mvcTestResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.access_token");

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk();
    }

    @Test
    void deveRecusarQuandoAudienciaForOutra() {

        String token = this.gerarToken("system-a", ESCOPO_COMPLETO, "other-service", this.localDevProperties.emissor(), Instant.now().plusSeconds(300));

        this.assertNaoAutenticado(token);
    }

    @Test
    void deveRecusarQuandoEmissorForOutro() {

        String token = this.gerarToken("system-a", ESCOPO_COMPLETO, AUDIENCIA, "https://evil.example", Instant.now().plusSeconds(300));

        this.assertNaoAutenticado(token);
    }

    @Test
    void deveRecusarQuandoTokenEstiverExpirado() {

        String token = this.gerarToken("system-a", ESCOPO_COMPLETO, AUDIENCIA, this.localDevProperties.emissor(), Instant.now().minusSeconds(600));

        this.assertNaoAutenticado(token);
    }

    @Test
    void deveRecusarQuandoAssinaturaForAlterada() {

        String token = this.gerarToken("system-a", ESCOPO_COMPLETO, AUDIENCIA, this.localDevProperties.emissor(), Instant.now().plusSeconds(300));

        this.assertNaoAutenticado(token.substring(0, token.length() - 4) + "AAAA");
    }

    @Test
    void deveExporHealthEMetricasSemTokenQuandoForActuator() {

        assertThat(this.mockMvcTester.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(this.mockMvcTester.get().uri("/actuator/health/readiness")).hasStatusOk();
        assertThat(this.mockMvcTester.get().uri("/actuator/prometheus")).hasStatusOk().bodyText().contains("activation_requests{");
    }

    private void assertNaoAutenticado(String token) {

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private String gerarToken(String clientId, String escopo, String audiencia, String emissor, Instant dataExpiracao) {

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .issuer(emissor)
                .subject(clientId)
                .audience(List.of(audiencia))
                .issuedAt(dataExpiracao.minusSeconds(3600))
                .expiresAt(dataExpiracao)
                .claim("client_id", clientId)
                .claim("scope", escopo)
                .build();

        return this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), jwtClaimsSet)).getTokenValue();
    }

}
