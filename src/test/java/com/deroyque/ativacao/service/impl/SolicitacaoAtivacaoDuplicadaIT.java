package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.dto.CriarSolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.service.SolicitacaoAtivacaoService;
import com.deroyque.ativacao.support.IntegrationTest;
import com.deroyque.ativacao.vo.ResultadoCriacaoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class SolicitacaoAtivacaoDuplicadaIT {

    private static final String SISTEMA_A = "system-a";
    private static final Integer QUANTIDADE_REQUISICAO_SIMULTANEA = 16;

    @Autowired
    private SolicitacaoAtivacaoService solicitacaoAtivacaoService;

    @Autowired
    private SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void limparBanco() {

        this.solicitacaoAtivacaoRepository.deleteAllInBatch();
    }

    @Test
    void deveDevolverRegistroExistenteSemCriarOutroQuandoRetryTiverMesmoConteudo() {

        ResultadoCriacaoVO primeiraCriacao = this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO("EXT-1", "INTERNET-500"));
        ResultadoCriacaoVO retry = this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO("EXT-1", "INTERNET-500"));

        assertThat(primeiraCriacao.criada()).isTrue();
        assertThat(retry.criada()).isFalse();
        assertThat(retry.solicitacaoAtivacaoDTO().id()).isEqualTo(primeiraCriacao.solicitacaoAtivacaoDTO().id());
        assertThat(this.solicitacaoAtivacaoRepository.count()).isEqualTo(1);
    }

    @Test
    void deveLancarDuplicidadeQuandoExternalIdVierComOutroConteudo() {

        ResultadoCriacaoVO primeiraCriacao = this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO("EXT-1", "INTERNET-500"));

        assertThatThrownBy(() -> this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO("EXT-1", "TV-100")))
                .isInstanceOfSatisfying(BusinessServiceException.class, businessServiceException -> {

                    assertThat(businessServiceException.getErroEnum()).isEqualTo(ErroEnum.SOLICITACAO_DUPLICADA);
                    assertThat(businessServiceException.getPropriedades()).containsEntry("existingRequestId", primeiraCriacao.solicitacaoAtivacaoDTO().id());
                });
        assertThat(this.solicitacaoAtivacaoRepository.count()).isEqualTo(1);
    }

    @Test
    void deveCriarOutroRegistroQuandoExternalIdForDeOutroSistema() {

        this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO("EXT-1", "INTERNET-500"));

        ResultadoCriacaoVO criacaoOutroSistema = this.solicitacaoAtivacaoService.criar("system-b", "corr", criarDTO("EXT-1", "INTERNET-500"));

        assertThat(criacaoOutroSistema.criada()).isTrue();
        assertThat(this.solicitacaoAtivacaoRepository.count()).isEqualTo(2);
    }

    @Test
    void deveCriarUmRegistroSoQuandoRequisicoesIguaisChegaremJuntas() throws Exception {

        String externalId = "EXT-" + UUID.randomUUID();
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ResultadoCriacaoVO>> listaFuturo = new ArrayList<>();

        try (ExecutorService executorService = Executors.newFixedThreadPool(QUANTIDADE_REQUISICAO_SIMULTANEA)) {

            for (int requisicao = 0; requisicao < QUANTIDADE_REQUISICAO_SIMULTANEA; requisicao++) {

                listaFuturo.add(executorService.submit(() -> {

                    largada.await();

                    return this.solicitacaoAtivacaoService.criar(SISTEMA_A, "corr", criarDTO(externalId, "INTERNET-500"));
                }));
            }

            largada.countDown();

            List<ResultadoCriacaoVO> listaResultado = new ArrayList<>();

            for (Future<ResultadoCriacaoVO> futuro : listaFuturo) {

                listaResultado.add(futuro.get());
            }

            assertThat(listaResultado).extracting(resultado -> resultado.solicitacaoAtivacaoDTO().id())
                    .containsOnly(listaResultado.getFirst().solicitacaoAtivacaoDTO().id());
            assertThat(listaResultado).filteredOn(ResultadoCriacaoVO::criada).hasSize(1);
            assertThat(this.solicitacaoAtivacaoRepository.count()).isEqualTo(1);
        }
    }

    private static CriarSolicitacaoAtivacaoDTO criarDTO(String externalId, String productCode) {

        return new CriarSolicitacaoAtivacaoDTO(externalId, "CUST-1", productCode);
    }

}
