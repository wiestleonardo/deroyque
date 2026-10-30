package com.deroyque.ativacao;

import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.dto.PaginaDTO;
import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.service.SistemaAutenticadoService;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SolicitacaoAtivacaoPostgresIT {

    private static final String IMAGEM_POSTGRESQL = "postgres:16-alpine";
    private static final Integer QUANTIDADE_REQUISICAO_SIMULTANEA = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(IMAGEM_POSTGRESQL);

    @Autowired
    private SolicitacaoAtivacaoService solicitacaoAtivacaoService;

    @Autowired
    private SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SistemaAutenticadoService sistemaAutenticadoService;

    @Test
    void deveCriarUmRegistroSoQuandoRequisicoesIguaisChegaremJuntasNoPostgres() throws Exception {

        String externalId = "EXT-" + UUID.randomUUID();
        given(sistemaAutenticadoService.adquirirSistemaOrigem()).willReturn("system-a");
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ResultadoCriacaoVO>> listaFuturo = new ArrayList<>();

        try (ExecutorService executorService = Executors.newFixedThreadPool(QUANTIDADE_REQUISICAO_SIMULTANEA)) {

            for (int requisicao = 0; requisicao < QUANTIDADE_REQUISICAO_SIMULTANEA; requisicao++) {

                listaFuturo.add(executorService.submit(() -> {

                    largada.await();

                    return solicitacaoAtivacaoService.criar(new CriarSolicitacaoAtivacaoDTO(externalId, "CUST-1", "INTERNET-500"));
                }));
            }

            largada.countDown();

            List<ResultadoCriacaoVO> listaResultado = new ArrayList<>();

            for (Future<ResultadoCriacaoVO> futuro : listaFuturo) {

                listaResultado.add(futuro.get());
            }

            assertThat(listaResultado).filteredOn(ResultadoCriacaoVO::criada).hasSize(1);
            assertThat(solicitacaoAtivacaoRepository.findAll())
                    .filteredOn(solicitacaoAtivacao -> solicitacaoAtivacao.getCodigoExterno().equals(externalId))
                    .hasSize(1);
        }
    }

    @Test
    void deveListarSomenteDoSistemaQuandoFiltrarPorStatusNoPostgres() {

        this.criarComo("system-a", new CriarSolicitacaoAtivacaoDTO("EXT-" + UUID.randomUUID(), "CUST-9", "INTERNET-500"));
        this.criarComo("system-b", new CriarSolicitacaoAtivacaoDTO("EXT-" + UUID.randomUUID(), "CUST-9", "INTERNET-500"));

        assertThat(this.listarComo("system-a", SituacaoSolicitacaoAtivacaoEnum.PENDING, "CUST-9").content()).hasSize(1);
        assertThat(this.listarComo("system-a", null, null).content()).isNotEmpty();
    }

    private void criarComo(String codigoSistemaOrigem, CriarSolicitacaoAtivacaoDTO criarSolicitacaoAtivacaoDTO) {

        given(sistemaAutenticadoService.adquirirSistemaOrigem()).willReturn(codigoSistemaOrigem);

        solicitacaoAtivacaoService.criar(criarSolicitacaoAtivacaoDTO);
    }

    private PaginaDTO<SolicitacaoAtivacaoDTO> listarComo(String codigoSistemaOrigem, SituacaoSolicitacaoAtivacaoEnum situacao, String codigoCliente) {

        given(sistemaAutenticadoService.adquirirSistemaOrigem()).willReturn(codigoSistemaOrigem);

        return solicitacaoAtivacaoService.listar(situacao, codigoCliente, 0, 20);
    }

}
