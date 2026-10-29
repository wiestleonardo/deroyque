package com.deroyque.ativacao.repository.domain;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolicitacaoAtivacaoTest {

    private static final Instant AGORA = Instant.parse("2026-10-26T14:03:11Z");
    private static final Duration TEMPO_RESERVA = Duration.ofSeconds(30);
    private static final String MOTIVO_ERRO_SERVIDOR = "PROVIDER_HTTP_500";

    @Test
    void deveIniciarPendenteEProntaParaProcessarQuandoCriada() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertThat(solicitacaoAtivacao.getIdSolicitacaoAtivacao()).isNotNull();
        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDENTE);
        assertThat(solicitacaoAtivacao.getQuantidadeTentativa()).isZero();
        assertThat(solicitacaoAtivacao.getDataCadastro()).isEqualTo(AGORA);
        assertThat(solicitacaoAtivacao.getDataManutencao()).isEqualTo(AGORA);
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA)).isTrue();
    }

    @Test
    void deveContarTentativaEReservarQuandoIniciarProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA);

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PROCESSANDO);
        assertThat(solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(1);
        assertThat(solicitacaoAtivacao.getDataProximaTentativa()).isEqualTo(AGORA.plus(TEMPO_RESERVA));
        assertThat(solicitacaoAtivacao.isReservaExpirada(AGORA)).isFalse();
        assertThat(solicitacaoAtivacao.isReservaExpirada(AGORA.plus(TEMPO_RESERVA))).isTrue();
    }

    @Test
    void deveFicarConcluidaQuandoProcessamentoTerminarComSucesso() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        solicitacaoAtivacao.concluir(AGORA.plusSeconds(1));

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.CONCLUIDA);
        assertThat(solicitacaoAtivacao.getDataManutencao()).isEqualTo(AGORA.plusSeconds(1));
    }

    @Test
    void deveVoltarParaPendenteComNovaDataQuandoFalhaForTemporaria() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        solicitacaoAtivacao.agendarNovaTentativa(MOTIVO_ERRO_SERVIDOR, AGORA.plusSeconds(10), AGORA);

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDENTE);
        assertThat(solicitacaoAtivacao.getMotivoFalha()).isEqualTo(MOTIVO_ERRO_SERVIDOR);
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA)).isFalse();
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA.plusSeconds(10))).isTrue();
    }

    @Test
    void deveVoltarParaFilaQuandoReservaExpirar() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();
        Instant aposReserva = AGORA.plus(TEMPO_RESERVA).plusSeconds(1);

        solicitacaoAtivacao.liberarReservaExpirada("PROCESSING_LEASE_EXPIRED", aposReserva);

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDENTE);
        assertThat(solicitacaoAtivacao.getMotivoFalha()).isEqualTo("PROCESSING_LEASE_EXPIRED");
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(aposReserva)).isTrue();
        assertThat(solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(1);
    }

    @Test
    void deveLancarErroQuandoLiberarReservaAindaValida() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        assertErroTransicao(() -> solicitacaoAtivacao.liberarReservaExpirada("PROCESSING_LEASE_EXPIRED", AGORA.plusSeconds(1)));
    }

    @Test
    void deveLimitarTamanhoQuandoMotivoFalhaForLongo() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        solicitacaoAtivacao.falhar("x".repeat(500), AGORA);

        assertThat(solicitacaoAtivacao.getMotivoFalha()).hasSize(SolicitacaoAtivacao.TAMANHO_MAXIMO_MOTIVO_FALHA);
    }

    @Test
    void deveCompararClienteEProdutoQuandoVerificarMesmoConteudo() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertThat(solicitacaoAtivacao.possuiMesmoConteudo("CUST-1", "INTERNET-500")).isTrue();
        assertThat(solicitacaoAtivacao.possuiMesmoConteudo("CUST-1", "TV-100")).isFalse();
        assertThat(solicitacaoAtivacao.possuiMesmoConteudo("CUST-2", "INTERNET-500")).isFalse();
    }

    @Test
    void deveLancarErroQuandoConcluirSemProcessar() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertErroTransicao(() -> solicitacaoAtivacao.concluir(AGORA));
    }

    @Test
    void deveLancarErroQuandoFalharSemProcessar() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertErroTransicao(() -> solicitacaoAtivacao.falhar(MOTIVO_ERRO_SERVIDOR, AGORA));
    }

    @Test
    void deveLancarErroQuandoConcluidaVoltarParaProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();
        solicitacaoAtivacao.concluir(AGORA);

        assertErroTransicao(() -> solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA));
    }

    @Test
    void deveLancarErroQuandoFalhaReceberNovaTentativa() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();
        solicitacaoAtivacao.falhar("PROVIDER_REJECTED_400", AGORA);

        assertErroTransicao(() -> solicitacaoAtivacao.agendarNovaTentativa(MOTIVO_ERRO_SERVIDOR, AGORA, AGORA));
        assertThat(solicitacaoAtivacao.getSituacao().isFinal()).isTrue();
    }

    @Test
    void deveLancarErroQuandoIniciarProcessamentoDuasVezes() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        assertErroTransicao(() -> solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA));
    }

    private static SolicitacaoAtivacao novaSolicitacao() {

        return SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", AGORA);
    }

    private static SolicitacaoAtivacao solicitacaoEmProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();
        solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA);

        return solicitacaoAtivacao;
    }

    private static void assertErroTransicao(Runnable acao) {

        assertThatThrownBy(acao::run)
                .isInstanceOf(BusinessServiceException.class)
                .extracting(excecao -> ((BusinessServiceException) excecao).getErroEnum())
                .isEqualTo(ErroEnum.TRANSICAO_SITUACAO_INVALIDA);
    }

}
