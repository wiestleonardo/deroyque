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

    @Test
    void deveIniciarPendenteEProntaParaProcessarQuandoCriada() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDING);
        assertThat(solicitacaoAtivacao.getQuantidadeTentativa()).isZero();
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA)).isTrue();
    }

    @Test
    void deveContarTentativaEReservarQuandoIniciarProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PROCESSING);
        assertThat(solicitacaoAtivacao.getQuantidadeTentativa()).isEqualTo(1);
        assertThat(solicitacaoAtivacao.isReservaExpirada(AGORA)).isFalse();
        assertThat(solicitacaoAtivacao.isReservaExpirada(AGORA.plus(TEMPO_RESERVA))).isTrue();
    }

    @Test
    void deveVoltarParaPendenteComNovaDataQuandoAgendarNovaTentativa() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();

        solicitacaoAtivacao.agendarNovaTentativa("PROVIDER_HTTP_500", AGORA.plusSeconds(10), AGORA);

        assertThat(solicitacaoAtivacao.getSituacao()).isEqualTo(SituacaoSolicitacaoAtivacaoEnum.PENDING);
        assertThat(solicitacaoAtivacao.getMotivoFalha()).isEqualTo("PROVIDER_HTTP_500");
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA)).isFalse();
        assertThat(solicitacaoAtivacao.isProntaParaProcessar(AGORA.plusSeconds(10))).isTrue();
    }

    @Test
    void deveCompararClienteEProdutoQuandoVerificarMesmoConteudo() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertThat(solicitacaoAtivacao.possuiMesmoConteudo("CUST-1", "INTERNET-500")).isTrue();
        assertThat(solicitacaoAtivacao.possuiMesmoConteudo("CUST-1", "TV-100")).isFalse();
    }

    @Test
    void deveRecusarQuandoConcluirSemProcessar() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();

        assertTransicaoInvalida(() -> solicitacaoAtivacao.concluir(AGORA));
    }

    @Test
    void deveRecusarQuandoConcluidaVoltarParaProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();
        solicitacaoAtivacao.concluir(AGORA);

        assertTransicaoInvalida(() -> solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA));
    }

    @Test
    void deveRecusarQuandoFalhaReceberNovaTentativa() {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoEmProcessamento();
        solicitacaoAtivacao.falhar("PROVIDER_REJECTED_400", AGORA);

        assertTransicaoInvalida(() -> solicitacaoAtivacao.agendarNovaTentativa("PROVIDER_HTTP_500", AGORA, AGORA));
    }

    private static SolicitacaoAtivacao novaSolicitacao() {

        return SolicitacaoAtivacao.criar("system-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", AGORA);
    }

    private static SolicitacaoAtivacao solicitacaoEmProcessamento() {

        SolicitacaoAtivacao solicitacaoAtivacao = novaSolicitacao();
        solicitacaoAtivacao.iniciarProcessamento(AGORA, TEMPO_RESERVA);

        return solicitacaoAtivacao;
    }

    private static void assertTransicaoInvalida(Runnable acao) {

        assertThatThrownBy(acao::run)
                .isInstanceOf(BusinessServiceException.class)
                .extracting(excecao -> ((BusinessServiceException) excecao).getErroEnum())
                .isEqualTo(ErroEnum.TRANSICAO_SITUACAO_INVALIDA);
    }

}
