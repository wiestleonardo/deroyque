package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.config.ProcessamentoProperties;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.IntegracaoException;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class ProcessamentoSolicitacaoAtivacaoServiceImplTest {

    private static final Instant AGORA = Instant.parse("2026-10-26T14:00:00Z");
    private static final ProcessamentoProperties PROCESSAMENTO_PROPERTIES = new ProcessamentoProperties(3, Duration.ofSeconds(30),
            Duration.ofSeconds(10), Duration.ofMinutes(5), 20);

    @Mock
    private SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    @Mock
    private ProvedorAtivacaoIntercommService provedorAtivacaoIntercommService;

    private ProcessamentoSolicitacaoAtivacaoServiceImpl processamentoSolicitacaoAtivacaoServiceImpl;

    private SolicitacaoAtivacao solicitacaoAtivacao;

    @BeforeEach
    void configurar() {

        this.processamentoSolicitacaoAtivacaoServiceImpl = new ProcessamentoSolicitacaoAtivacaoServiceImpl(solicitacaoAtivacaoRepository,
                provedorAtivacaoIntercommService, PROCESSAMENTO_PROPERTIES, Clock.fixed(AGORA, ZoneOffset.UTC));
        this.solicitacaoAtivacao = SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr", AGORA.minusSeconds(60));
    }

    @Test
    void deveConcluirQuandoProvedorAtivar() {

        this.prepararGravacao();

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.COMPLETED);
        assertThat(this.solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(1);
    }

    @Test
    void deveFalharSemNovaTentativaQuandoProvedorRecusar() {

        this.prepararGravacao();
        this.prepararFalhaProvedor(new IntegracaoException("PROVIDER_REJECTED_400", false, null));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.FAILED);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("PROVIDER_REJECTED_400");
    }

    @Test
    void deveAgendarNovaTentativaComEsperaQuandoFalhaPermitirNovaTentativa() {

        this.prepararGravacao();
        this.prepararFalhaProvedor(new IntegracaoException("PROVIDER_HTTP_500", true, null));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDING);
        assertThat(this.solicitacaoAtivacao.getDataProximaTentativa()).isBetween(AGORA.plusSeconds(10), AGORA.plusSeconds(15));
    }

    @Test
    void deveUsarRetryAfterQuandoProvedorInformar() {

        this.prepararGravacao();
        this.prepararFalhaProvedor(new IntegracaoException("PROVIDER_HTTP_503", true, Duration.ofSeconds(42)));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getDataProximaTentativa()).isEqualTo(AGORA.plusSeconds(42));
    }

    @Test
    void deveFalharQuandoUltimaTentativaFalhar() {

        this.usarTodasTentativasMenosUma();
        this.prepararGravacao();
        this.prepararFalhaProvedor(new IntegracaoException("PROVIDER_TIMEOUT", true, null));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.FAILED);
        assertThat(this.solicitacaoAtivacao.getMotivoFalha()).isEqualTo("MAX_ATTEMPTS_EXCEEDED:PROVIDER_TIMEOUT");
    }

    @Test
    void deveIgnorarQuandoOutraInstanciaReservarAntes() {

        given(solicitacaoAtivacaoRepository.saveAndFlush(this.solicitacaoAtivacao))
                .willThrow(new ObjectOptimisticLockingFailureException(SolicitacaoAtivacao.class, this.solicitacaoAtivacao.getIdSolicitacaoAtivacao()));

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        then(provedorAtivacaoIntercommService).shouldHaveNoInteractions();
    }

    @Test
    void deveReprocessarQuandoReservaExpirar() {

        this.solicitacaoAtivacao.iniciarProcessamento(AGORA.minusSeconds(60), Duration.ofSeconds(30));
        this.prepararGravacao();

        this.processamentoSolicitacaoAtivacaoServiceImpl.processar(this.solicitacaoAtivacao);

        assertThat(this.solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.COMPLETED);
        assertThat(this.solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(2);
    }

    private void prepararGravacao() {

        given(solicitacaoAtivacaoRepository.saveAndFlush(this.solicitacaoAtivacao)).willReturn(this.solicitacaoAtivacao);
    }

    private void prepararFalhaProvedor(IntegracaoException integracaoException) {

        willThrow(integracaoException).given(provedorAtivacaoIntercommService).ativar(any(SolicitacaoAtivacao.class));
    }

    private void usarTodasTentativasMenosUma() {

        Instant passado = AGORA.minusSeconds(600);

        for (int tentativa = 1; tentativa < PROCESSAMENTO_PROPERTIES.quantidadeMaximaTentativa(); tentativa++) {

            this.solicitacaoAtivacao.iniciarProcessamento(passado, Duration.ofSeconds(1));
            this.solicitacaoAtivacao.agendarNovaTentativa("PROVIDER_HTTP_500", passado, passado);
        }
    }

}
