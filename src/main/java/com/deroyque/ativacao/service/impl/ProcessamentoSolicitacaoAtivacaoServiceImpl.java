package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.config.ProcessamentoProperties;
import com.deroyque.ativacao.constants.MotivoFalhaConstants;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.IntegracaoException;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.service.ProcessamentoSolicitacaoAtivacaoService;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessamentoSolicitacaoAtivacaoServiceImpl implements ProcessamentoSolicitacaoAtivacaoService {

    private static final List<SituacaoSolicitacaoAtivacaoEnum> LISTA_SITUACAO_PROCESSAVEL = List.of(SituacaoSolicitacaoAtivacaoEnum.PENDING,
            SituacaoSolicitacaoAtivacaoEnum.PROCESSING);

    private final SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    private final ProvedorAtivacaoIntercommService provedorAtivacaoIntercommService;

    private final ProcessamentoProperties processamentoProperties;

    private final Clock clock;

    @Override
    public void processarPendentes() {

        // Uma consulta traz o lote inteiro.
        List<SolicitacaoAtivacao> listaSolicitacaoAtivacao = solicitacaoAtivacaoRepository.listarProntoParaProcessar(LISTA_SITUACAO_PROCESSAVEL,
                clock.instant(), Limit.of(processamentoProperties.tamanhoLote()));

        // Cada solicitação do lote chama o provedor em paralelo, numa thread virtual; o lote dura o tempo da chamada mais lenta,
        // não a soma de todas. O close do executor espera todas terminarem antes da próxima execução do job.
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            listaSolicitacaoAtivacao.forEach(solicitacaoAtivacao -> executorService.execute(() -> this.processarSemInterromperLote(solicitacaoAtivacao)));
        }
    }

    @Override
    public void processar(SolicitacaoAtivacao solicitacaoAtivacao) {

        SolicitacaoAtivacao solicitacaoAtivacaoReservada = this.reservar(solicitacaoAtivacao);

        if (solicitacaoAtivacaoReservada == null) {

            return;
        }

        CorrelacaoUtil.definir(solicitacaoAtivacaoReservada.getCodigoCorrelacao() == null ? solicitacaoAtivacaoReservada.getIdSolicitacaoAtivacao().toString()
                : solicitacaoAtivacaoReservada.getCodigoCorrelacao());

        try {

            this.chamarProvedor(solicitacaoAtivacaoReservada);

        } finally {

            CorrelacaoUtil.limpar();
        }
    }

    private void processarSemInterromperLote(SolicitacaoAtivacao solicitacaoAtivacao) {

        try {

            this.processar(solicitacaoAtivacao);

        } catch (RuntimeException runtimeException) {

            log.error("Falha não prevista ao processar a solicitação {}", solicitacaoAtivacao.getIdSolicitacaoAtivacao(), runtimeException);
        }
    }

    // O provedor é chamado fora de transação; o @Version garante que só uma instância reserve e grave cada solicitação.
    private SolicitacaoAtivacao reservar(SolicitacaoAtivacao solicitacaoAtivacao) {

        Instant agora = clock.instant();

        if (solicitacaoAtivacao.isReservaExpirada(agora)) {

            log.warn("Reserva expirada na solicitação {} após {} tentativas", solicitacaoAtivacao.getIdSolicitacaoAtivacao(),
                    solicitacaoAtivacao.getQuantidadeTentativa());

            if (solicitacaoAtivacao.getQuantidadeTentativa() >= processamentoProperties.quantidadeMaximaTentativa()) {

                solicitacaoAtivacao.falhar(MotivoFalhaConstants.PREFIXO_TENTATIVAS_ESGOTADAS + MotivoFalhaConstants.RESERVA_EXPIRADA, agora);
                this.gravar(solicitacaoAtivacao);
                return null;
            }

            solicitacaoAtivacao.agendarNovaTentativa(MotivoFalhaConstants.RESERVA_EXPIRADA, agora, agora);
        }

        if (!solicitacaoAtivacao.isProntaParaProcessar(agora)) {

            return null;
        }

        solicitacaoAtivacao.iniciarProcessamento(agora, processamentoProperties.tempoReserva());

        return this.gravar(solicitacaoAtivacao);
    }

    private void chamarProvedor(SolicitacaoAtivacao solicitacaoAtivacao) {

        try {

            provedorAtivacaoIntercommService.ativar(solicitacaoAtivacao);
            solicitacaoAtivacao.concluir(clock.instant());

            log.info("Solicitação {} concluída na tentativa {}", solicitacaoAtivacao.getIdSolicitacaoAtivacao(), solicitacaoAtivacao.getQuantidadeTentativa());

        } catch (IntegracaoException integracaoException) {

            this.tratarFalha(solicitacaoAtivacao, integracaoException);
        }

        this.gravar(solicitacaoAtivacao);
    }

    private void tratarFalha(SolicitacaoAtivacao solicitacaoAtivacao, IntegracaoException integracaoException) {

        Instant agora = clock.instant();

        log.warn("Falha ao chamar o provedor na solicitação {}, tentativa {}: {}", solicitacaoAtivacao.getIdSolicitacaoAtivacao(),
                solicitacaoAtivacao.getQuantidadeTentativa(), integracaoException.getMotivo());

        if (!integracaoException.getPermiteNovaTentativa()) {

            solicitacaoAtivacao.falhar(integracaoException.getMotivo(), agora);
            return;
        }

        if (solicitacaoAtivacao.getQuantidadeTentativa() >= processamentoProperties.quantidadeMaximaTentativa()) {

            solicitacaoAtivacao.falhar(MotivoFalhaConstants.PREFIXO_TENTATIVAS_ESGOTADAS + integracaoException.getMotivo(), agora);
            return;
        }

        solicitacaoAtivacao.agendarNovaTentativa(integracaoException.getMotivo(),
                agora.plus(this.calcularEspera(solicitacaoAtivacao.getQuantidadeTentativa(), integracaoException.getEsperaSolicitada())), agora);
    }

    // Backoff exponencial com variação aleatória, limitado à espera máxima; o Retry-After do provedor tem prioridade.
    private Duration calcularEspera(Integer quantidadeTentativa, Duration esperaSolicitada) {

        if (esperaSolicitada != null) {

            return esperaSolicitada.compareTo(processamentoProperties.esperaMaxima()) > 0 ? processamentoProperties.esperaMaxima() : esperaSolicitada;
        }

        Duration espera = processamentoProperties.esperaInicial().multipliedBy(1L << Math.min(quantidadeTentativa - 1, 10));

        if (espera.compareTo(processamentoProperties.esperaMaxima()) > 0) {

            espera = processamentoProperties.esperaMaxima();
        }

        return espera.plusMillis(ThreadLocalRandom.current().nextLong(espera.toMillis() / 2 + 1));
    }

    private SolicitacaoAtivacao gravar(SolicitacaoAtivacao solicitacaoAtivacao) {

        try {

            return solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao);

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.info("Solicitação {} foi alterada por outra instância", solicitacaoAtivacao.getIdSolicitacaoAtivacao());

            return null;
        }
    }

}
