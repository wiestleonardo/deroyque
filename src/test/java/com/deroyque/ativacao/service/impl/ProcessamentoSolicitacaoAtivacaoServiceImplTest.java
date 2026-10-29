package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.config.ProcessamentoProperties;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.vo.PoliticaRetentativaVO;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaDefinitiva;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaTemporaria;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.Sucesso;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ProcessamentoSolicitacaoAtivacaoServiceImplTest {

    private static final Instant AGORA = Instant.parse("2026-10-26T14:00:00Z");
    private static final Integer QUANTIDADE_MAXIMA_TENTATIVA = 3;
    private static final ProcessamentoProperties PROCESSAMENTO_PROPERTIES = new ProcessamentoProperties(QUANTIDADE_MAXIMA_TENTATIVA,
            Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5), 20, 5);

    @Mock
    private SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    @Mock
    private ProvedorAtivacaoIntercommService provedorAtivacaoIntercommService;

    @Mock
    private PlatformTransactionManager platformTransactionManager;

    private final SimpleMeterRegistry simpleMeterRegistry = new SimpleMeterRegistry();

    private ProcessamentoSolicitacaoAtivacaoServiceImpl processamentoSolicitacaoAtivacaoServiceImpl;

    private SolicitacaoAtivacao solicitacaoAtivacao;

    @BeforeEach
    void configurar() {

        PoliticaRetentativaVO politicaRetentativaVO = new PoliticaRetentativaVO(Duration.ofSeconds(10), Duration.ofMinutes(5), new Random(1));

        this.processamentoSolicitacaoAtivacaoServiceImpl = new ProcessamentoSolicitacaoAtivacaoServiceImpl(this.solicitacaoAtivacaoRepository,
                this.provedorAtivacaoIntercommService, politicaRetentativaVO, PROCESSAMENTO_PROPERTIES,
                new TransactionTemplate(this.platformTransactionManager), Clock.fixed(AGORA, ZoneOffset.UTC), this.simpleMeterRegistry);

        this.solicitacaoAtivacao = SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr", AGORA.minusSeconds(60));
    }

    @Test
    void deveConcluirQuandoProvedorAtivar() {

        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao)).willReturn(new Sucesso());

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.CONCLUIDA);
        assertThat(this.solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(1);
        assertThat(this.simpleMeterRegistry.get("activation.provider.calls").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void deveFalharSemNovaTentativaQuandoProvedorRecusar() {

        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao)).willReturn(new FalhaDefinitiva("PROVIDER_REJECTED_400"));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.FALHA);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("PROVIDER_REJECTED_400");
    }

    @Test
    void deveAgendarNovaTentativaComEsperaQuandoFalhaForTemporaria() {

        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao)).willReturn(new FalhaTemporaria("PROVIDER_HTTP_500"));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDENTE);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(this.solicitacaoAtivacao.getDataProximaTentativa()).isBetween(AGORA.plusSeconds(5), AGORA.plusSeconds(10));
    }

    @Test
    void deveRespeitarRetryAfterQuandoProvedorInformarEspera() {

        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao))
                .willReturn(new FalhaTemporaria("PROVIDER_HTTP_429", Duration.ofSeconds(42)));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getDataProximaTentativa()).isEqualTo(AGORA.plusSeconds(42));
    }

    @Test
    void deveFalharQuandoFalhaTemporariaOcorrerNaUltimaTentativa() {

        this.esgotarTentativasAteAUltima();
        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao)).willReturn(new FalhaTemporaria("PROVIDER_TIMEOUT"));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(QUANTIDADE_MAXIMA_TENTATIVA);
        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.FALHA);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT");
    }

    @Test
    void deveIgnorarQuandoNovaTentativaAindaNaoVenceu() {

        this.solicitacaoAtivacao.iniciarProcessamento(AGORA.minusSeconds(5), Duration.ofSeconds(1));
        this.solicitacaoAtivacao.agendarNovaTentativa("PROVIDER_HTTP_500", AGORA.plusSeconds(60), AGORA.minusSeconds(4));
        this.prepararBusca();

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        then(this.provedorAtivacaoIntercommService).shouldHaveNoInteractions();
        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDENTE);
    }

    @Test
    void deveIgnorarQuandoSolicitacaoJaEstiverConcluida() {

        this.solicitacaoAtivacao.iniciarProcessamento(AGORA.minusSeconds(5), Duration.ofSeconds(1));
        this.solicitacaoAtivacao.concluir(AGORA.minusSeconds(4));
        this.prepararBusca();

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        then(this.provedorAtivacaoIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void deveIgnorarQuandoOutraInstanciaReservarAntes() {

        this.prepararBusca();
        given(this.solicitacaoAtivacaoRepository.saveAndFlush(this.solicitacaoAtivacao))
                .willThrow(new ObjectOptimisticLockingFailureException(SolicitacaoAtivacao.class, this.solicitacaoAtivacao.getIdSolicitacaoAtivacao()));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        then(this.provedorAtivacaoIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void deveReprocessarQuandoReservaDeOutraInstanciaExpirar() {

        this.solicitacaoAtivacao.iniciarProcessamento(AGORA.minusSeconds(60), Duration.ofSeconds(30));
        this.prepararBuscaEGravacao();
        given(this.provedorAtivacaoIntercommService.ativar(this.solicitacaoAtivacao)).willReturn(new Sucesso());

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.CONCLUIDA);
        assertThat(this.solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(2);
    }

    @Test
    void deveFalharSemChamarProvedorQuandoReservaExpirarSemTentativasRestantes() {

        this.esgotarTentativasAteAUltima();
        this.solicitacaoAtivacao.iniciarProcessamento(AGORA.minusSeconds(60), Duration.ofSeconds(30));
        this.prepararBuscaEGravacao();

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao());

        then(this.provedorAtivacaoIntercommService).should(never()).ativar(any(SolicitacaoAtivacao.class));
        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.FALHA);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROCESSING_LEASE_EXPIRED");
    }

    private void prepararBusca() {

        given(this.solicitacaoAtivacaoRepository.findById(this.solicitacaoAtivacao.getIdSolicitacaoAtivacao()))
                .willReturn(Optional.of(this.solicitacaoAtivacao));
    }

    private void prepararBuscaEGravacao() {

        this.prepararBusca();
        given(this.solicitacaoAtivacaoRepository.saveAndFlush(this.solicitacaoAtivacao)).willReturn(this.solicitacaoAtivacao);
    }

    private void esgotarTentativasAteAUltima() {

        Instant passado = AGORA.minusSeconds(600);

        for (int tentativa = 1; tentativa < QUANTIDADE_MAXIMA_TENTATIVA; tentativa++) {

            this.solicitacaoAtivacao.iniciarProcessamento(passado, Duration.ofSeconds(1));
            this.solicitacaoAtivacao.agendarNovaTentativa("PROVIDER_HTTP_500", passado, passado);
        }
    }

}
