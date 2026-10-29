package com.deroyque.ativacao.api;

import com.deroyque.ativacao.api.impl.SolicitacaoAtivacaoControllerImpl;
import com.deroyque.ativacao.config.CorrelacaoFilter;
import com.deroyque.ativacao.config.ProblemDetailFactory;
import com.deroyque.ativacao.config.SecurityConfig;
import com.deroyque.ativacao.config.TamanhoRequisicaoFilter;
import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.support.TokenTeste;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(SolicitacaoAtivacaoControllerImpl.class)
@Import({SecurityConfig.class, ProblemDetailFactory.class, CorrelacaoFilter.class, TamanhoRequisicaoFilter.class})
class SolicitacaoAtivacaoControllerTest {

    private static final UUID ID_SOLICITACAO = UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90");
    private static final String CORPO_VALIDO = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;
    private static final CriarSolicitacaoAtivacaoDTO CRIAR_SOLICITACAO_ATIVACAO_DTO = new CriarSolicitacaoAtivacaoDTO("EXT-12345", "CUST-10001",
            "INTERNET-500");

    @Autowired
    private MockMvcTester mockMvcTester;

    @MockitoBean
    private SolicitacaoAtivacaoService solicitacaoAtivacaoService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void deveResponderCreatedComLocationQuandoCriarSolicitacao() {

        given(this.solicitacaoAtivacaoService.criar(eq(TokenTeste.SISTEMA_ORIGEM), anyString(), eq(CRIAR_SOLICITACAO_ATIVACAO_DTO)))
                .willReturn(new ResultadoCriacaoVO(solicitacaoPendente(), Boolean.TRUE));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);
        assertThat(mvcTestResult).headers().containsHeader("X-Correlation-Id");
        assertThat(mvcTestResult.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(RotaConstants.SolicitacaoAtivacao.BASE + "/" + ID_SOLICITACAO);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.id").isEqualTo(ID_SOLICITACAO.toString());
        assertThat(mvcTestResult).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-10-26T14:03:11Z");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.failureReason");
    }

    @Test
    void deveResponderOkSemLocationQuandoRetryTiverMesmoConteudo() {

        given(this.solicitacaoAtivacaoService.criar(eq(TokenTeste.SISTEMA_ORIGEM), anyString(), eq(CRIAR_SOLICITACAO_ATIVACAO_DTO)))
                .willReturn(new ResultadoCriacaoVO(solicitacaoPendente(), Boolean.FALSE));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO).exchange();

        assertThat(mvcTestResult).hasStatusOk();
        assertThat(mvcTestResult).headers().doesNotContainHeader(HttpHeaders.LOCATION);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.externalId").isEqualTo("EXT-12345");
    }

    @Test
    void deveResponderConflictQuandoExternalIdForReusadoComOutroConteudo() {

        UUID idExistente = UUID.randomUUID();
        given(this.solicitacaoAtivacaoService.criar(eq(TokenTeste.SISTEMA_ORIGEM), anyString(), eq(CRIAR_SOLICITACAO_ATIVACAO_DTO)))
                .willThrow(new BusinessServiceException(ErroEnum.SOLICITACAO_DUPLICADA, Map.of("existingRequestId", idExistente), "EXT-12345"));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.type").isEqualTo("https://api.exemplo.com/problems/duplicate-request");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.detail").asString().contains("EXT-12345");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.existingRequestId").isEqualTo(idExistente.toString());
    }

    @Test
    void deveResponderBadRequestComCamposQuandoCorpoForInvalido() {

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .header("X-Correlation-Id", "abc123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT 1","productCode":"%s"}
                        """.formatted("X".repeat(65)))
                .exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.title").isEqualTo("Dados inválidos");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.instance").isEqualTo(RotaConstants.SolicitacaoAtivacao.BASE);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.traceId").isEqualTo("abc123");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.errors[*].field").asArray().contains("customerId", "externalId", "productCode");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.errors[?(@.field == 'customerId')].message").asArray().containsExactly("é obrigatório");
        then(this.solicitacaoAtivacaoService).shouldHaveNoInteractions();
    }

    @Test
    void deveResponderBadRequestSemDetalheInternoQuandoJsonForMalformado() {

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content("{nao-e-json").exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvcTestResult).bodyJson().hasPath("$.traceId");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.trace");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.exception");
    }

    @Test
    void deveResponderPayloadTooLargeQuandoCorpoPassarDoLimite() {

        assertThat(this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(17 * 1024)))
                .hasStatus(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void deveResponderErroInternoSemDetalheQuandoOcorrerFalhaNaoPrevista() {

        given(this.solicitacaoAtivacaoService.criar(eq(TokenTeste.SISTEMA_ORIGEM), anyString(), eq(CRIAR_SOLICITACAO_ATIVACAO_DTO)))
                .willThrow(new IllegalStateException("db password=secret"));

        MvcTestResult mvcTestResult = this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.escrita())
                .contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.detail").isEqualTo("Ocorreu um erro inesperado. Informe o traceId ao suporte.");
        assertThat(mvcTestResult).bodyText().doesNotContain("secret");
    }

    @Test
    void deveResponderSolicitacaoQuandoExistir() {

        given(this.solicitacaoAtivacaoService.adquirir(TokenTeste.SISTEMA_ORIGEM, ID_SOLICITACAO)).willReturn(solicitacaoPendente());

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE + "/{id}", ID_SOLICITACAO).with(TokenTeste.leitura()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.id").isEqualTo(ID_SOLICITACAO.toString());
    }

    @Test
    void deveResponderNotFoundQuandoSolicitacaoNaoExistir() {

        given(this.solicitacaoAtivacaoService.adquirir(TokenTeste.SISTEMA_ORIGEM, ID_SOLICITACAO))
                .willThrow(new BusinessServiceException(ErroEnum.SOLICITACAO_NAO_ENCONTRADA));

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE + "/{id}", ID_SOLICITACAO).with(TokenTeste.leitura()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.status").isEqualTo(404);
    }

    @Test
    void deveResponderBadRequestQuandoIdNaoForUuid() {

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE + "/{id}", "nao-e-uuid").with(TokenTeste.leitura()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("id");
    }

    @Test
    void deveResponderBadRequestQuandoTamanhoPaginaPassarDoLimite() {

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE).param("size", "500").with(TokenTeste.leitura()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
    }

    @Test
    void deveResponderUnauthorizedQuandoNaoHouverToken() {

        assertThat(this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .headers().containsHeader(HttpHeaders.WWW_AUTHENTICATE);

        then(this.solicitacaoAtivacaoService).shouldHaveNoInteractions();
    }

    @Test
    void deveResponderForbiddenQuandoTokenDeLeituraTentarCriar() {

        assertThat(this.mockMvcTester.post().uri(RotaConstants.SolicitacaoAtivacao.BASE).with(TokenTeste.leitura())
                .contentType(MediaType.APPLICATION_JSON).content(CORPO_VALIDO))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.title").isEqualTo("Acesso negado");

        then(this.solicitacaoAtivacaoService).shouldHaveNoInteractions();
    }

    @Test
    void deveResponderForbiddenQuandoTokenDeEscritaTentarConsultar() {

        assertThat(this.mockMvcTester.get().uri(RotaConstants.SolicitacaoAtivacao.BASE + "/{id}", ID_SOLICITACAO).with(TokenTeste.escrita()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private static SolicitacaoAtivacaoDTO solicitacaoPendente() {

        Instant dataCadastro = Instant.parse("2026-10-26T14:03:11Z");

        return SolicitacaoAtivacaoDTO.builder()
                .id(ID_SOLICITACAO)
                .externalId("EXT-12345")
                .customerId("CUST-10001")
                .productCode("INTERNET-500")
                .status(SituacaoSolicitacaoAtivacaoEnum.PENDENTE)
                .attempts(0)
                .createdAt(dataCadastro)
                .updatedAt(dataCadastro)
                .build();
    }

}
