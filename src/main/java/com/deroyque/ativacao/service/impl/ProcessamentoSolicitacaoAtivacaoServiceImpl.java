package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.config.ProcessamentoProperties;
import com.deroyque.ativacao.constants.MotivoFalhaConstants;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.service.ProcessamentoSolicitacaoAtivacaoService;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import com.deroyque.ativacao.util.IntegerUtil;
import com.deroyque.ativacao.util.ObjectUtil;
import com.deroyque.ativacao.vo.PoliticaRetentativaVO;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaDefinitiva;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.FalhaTemporaria;
import com.deroyque.ativacao.vo.ResultadoAtivacaoVO.Sucesso;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessamentoSolicitacaoAtivacaoServiceImpl implements ProcessamentoSolicitacaoAtivacaoService {

    private static final List<SituacaoSolicitacaoAtivacaoEnum> LISTA_SITUACAO_PROCESSAVEL = List.of(SituacaoSolicitacaoAtivacaoEnum.PENDENTE,
            SituacaoSolicitacaoAtivacaoEnum.PROCESSANDO);
    private static final String METRICA_CHAMADA_PROVEDOR = "activation.provider.calls";
    private static final String METRICA_TRANSICAO = "activation.requests.transitions";
    private static final String TAG_RESULTADO = "outcome";
    private static final String TAG_SITUACAO = "status";

    private final SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository;

    private final ProvedorAtivacaoIntercommService provedorAtivacaoIntercommService;

    private final PoliticaRetentativaVO politicaRetentativaVO;

    private final ProcessamentoProperties processamentoProperties;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    private final MeterRegistry meterRegistry;

    @Override
    public void processarPendentes() {

        List<UUID> listaIdSolicitacaoAtivacao = this.solicitacaoAtivacaoRepository.listarIdProntoParaProcessar(LISTA_SITUACAO_PROCESSAVEL,
                this.clock.instant(), Limit.of(this.processamentoProperties.tamanhoLote()));

        if (listaIdSolicitacaoAtivacao.isEmpty()) {

            return;
        }

        // O limite fica abaixo do pool de conexões: virtual thread não limita quantas tarefas pedem conexão ao mesmo tempo.
        Semaphore semaphore = new Semaphore(this.processamentoProperties.limiteConcorrencia());

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            for (UUID idSolicitacaoAtivacao : listaIdSolicitacaoAtivacao) {

                executorService.submit(() -> this.processarComLimite(idSolicitacaoAtivacao, semaphore));
            }
        }
    }

    @Override
    public void processar(UUID idSolicitacaoAtivacao) {

        Optional<SolicitacaoAtivacao> solicitacaoAtivacaoReservada = this.reservar(idSolicitacaoAtivacao);

        if (solicitacaoAtivacaoReservada.isEmpty()) {

            return;
        }

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoAtivacaoReservada.get();
        String codigoCorrelacao = solicitacaoAtivacao.getCodigoCorrelacao();

        CorrelacaoUtil.definir(ObjectUtil.isNull(codigoCorrelacao) ? idSolicitacaoAtivacao.toString() : codigoCorrelacao);

        try {

            ResultadoAtivacaoVO resultadoAtivacaoVO = this.chamarProvedor(solicitacaoAtivacao);

            this.aplicarResultado(solicitacaoAtivacao, resultadoAtivacaoVO);

        } finally {

            CorrelacaoUtil.limpar();
        }
    }

    private void processarComLimite(UUID idSolicitacaoAtivacao, Semaphore semaphore) {

        try {

            semaphore.acquire();

        } catch (InterruptedException interruptedException) {

            Thread.currentThread().interrupt();
            return;
        }

        try {

            this.processar(idSolicitacaoAtivacao);

        } catch (RuntimeException runtimeException) {

            log.error("Falha não prevista ao processar a solicitação {}", idSolicitacaoAtivacao, runtimeException);

        } finally {

            semaphore.release();
        }
    }

    private Optional<SolicitacaoAtivacao> reservar(UUID idSolicitacaoAtivacao) {

        try {

            return this.transactionTemplate.execute(transactionStatus -> {

                Optional<SolicitacaoAtivacao> solicitacaoAtivacaoEncontrada = this.solicitacaoAtivacaoRepository.findById(idSolicitacaoAtivacao);

                if (solicitacaoAtivacaoEncontrada.isEmpty()) {

                    return Optional.empty();
                }

                SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoAtivacaoEncontrada.get();
                Instant agora = this.clock.instant();

                if (solicitacaoAtivacao.isReservaExpirada(agora)) {

                    log.warn("Reserva expirada na solicitação {} após {} tentativas", idSolicitacaoAtivacao, solicitacaoAtivacao.getQuantidadeTentativa());

                    if (this.isTentativasEsgotadas(solicitacaoAtivacao)) {

                        solicitacaoAtivacao.falhar(MotivoFalhaConstants.PREFIXO_TENTATIVAS_ESGOTADAS + MotivoFalhaConstants.RESERVA_EXPIRADA, agora);
                        this.solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao);
                        this.contarTransicao(solicitacaoAtivacao);

                        return Optional.empty();
                    }

                    solicitacaoAtivacao.liberarReservaExpirada(MotivoFalhaConstants.RESERVA_EXPIRADA, agora);
                }

                if (!solicitacaoAtivacao.isProntaParaProcessar(agora)) {

                    return Optional.empty();
                }

                solicitacaoAtivacao.iniciarProcessamento(agora, this.processamentoProperties.tempoReserva());

                return Optional.of(this.solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao));
            });

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.debug("Solicitação {} reservada por outra instância", idSolicitacaoAtivacao);

            return Optional.empty();
        }
    }

    private ResultadoAtivacaoVO chamarProvedor(SolicitacaoAtivacao solicitacaoAtivacao) {

        Timer.Sample sample = Timer.start(this.meterRegistry);

        ResultadoAtivacaoVO resultadoAtivacaoVO = this.provedorAtivacaoIntercommService.ativar(solicitacaoAtivacao);

        sample.stop(this.meterRegistry.timer(METRICA_CHAMADA_PROVEDOR, TAG_RESULTADO, this.adquirirTagResultado(resultadoAtivacaoVO)));

        log.info("Provedor respondeu {} para a solicitação {} na tentativa {}", resultadoAtivacaoVO, solicitacaoAtivacao.getIdSolicitacaoAtivacao(),
                solicitacaoAtivacao.getQuantidadeTentativa());

        return resultadoAtivacaoVO;
    }

    private void aplicarResultado(SolicitacaoAtivacao solicitacaoAtivacaoReservada, ResultadoAtivacaoVO resultadoAtivacaoVO) {

        try {

            this.transactionTemplate.executeWithoutResult(transactionStatus -> {

                SolicitacaoAtivacao solicitacaoAtivacao = this.solicitacaoAtivacaoRepository
                        .findById(solicitacaoAtivacaoReservada.getIdSolicitacaoAtivacao())
                        .orElseThrow();

                if (!ObjectUtil.isEquals(solicitacaoAtivacao.getNumeroVersao(), solicitacaoAtivacaoReservada.getNumeroVersao())) {

                    log.warn("Resultado descartado: a solicitação {} foi assumida por outra instância", solicitacaoAtivacao.getIdSolicitacaoAtivacao());
                    return;
                }

                Instant agora = this.clock.instant();

                switch (resultadoAtivacaoVO) {
                    case Sucesso sucesso -> solicitacaoAtivacao.concluir(agora);
                    case FalhaDefinitiva falhaDefinitiva -> solicitacaoAtivacao.falhar(falhaDefinitiva.motivo(), agora);
                    case FalhaTemporaria falhaTemporaria when this.isTentativasEsgotadas(solicitacaoAtivacao) ->
                            solicitacaoAtivacao.falhar(MotivoFalhaConstants.PREFIXO_TENTATIVAS_ESGOTADAS + falhaTemporaria.motivo(), agora);
                    case FalhaTemporaria falhaTemporaria -> solicitacaoAtivacao.agendarNovaTentativa(falhaTemporaria.motivo(),
                            agora.plus(this.politicaRetentativaVO.calcularEspera(solicitacaoAtivacao.getQuantidadeTentativa(),
                                    falhaTemporaria.esperaSolicitada())), agora);
                }

                this.solicitacaoAtivacaoRepository.saveAndFlush(solicitacaoAtivacao);
                this.contarTransicao(solicitacaoAtivacao);
            });

        } catch (ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException) {

            log.warn("Conflito ao gravar o resultado da solicitação {}; ela será reprocessada", solicitacaoAtivacaoReservada.getIdSolicitacaoAtivacao());
        }
    }

    private Boolean isTentativasEsgotadas(SolicitacaoAtivacao solicitacaoAtivacao) {

        return IntegerUtil.maiorOuIgual(solicitacaoAtivacao.getQuantidadeTentativa(), this.processamentoProperties.quantidadeMaximaTentativa());
    }

    private void contarTransicao(SolicitacaoAtivacao solicitacaoAtivacao) {

        this.meterRegistry.counter(METRICA_TRANSICAO, TAG_SITUACAO, solicitacaoAtivacao.getSituacao().getCodigo()).increment();
    }

    private String adquirirTagResultado(ResultadoAtivacaoVO resultadoAtivacaoVO) {

        return switch (resultadoAtivacaoVO) {
            case Sucesso sucesso -> "success";
            case FalhaDefinitiva falhaDefinitiva -> "permanent_failure";
            case FalhaTemporaria falhaTemporaria -> "transient_failure";
        };
    }

}
