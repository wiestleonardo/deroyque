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
import java.util.UUID;
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

        List<UUID> listaIdSolicitacaoAtivacao = solicitacaoAtivacaoRepository.listarIdProntoParaProcessar(LISTA_SITUACAO_PROCESSAVEL, clock.instant(),
                Limit.of(processamentoProperties.tamanhoLote()));

        for (UUID idSolicitacaoAtivacao : listaIdSolicitacaoAtivacao) {

            try {

                this.processar(idSolicitacaoAtivacao);

            } catch (RuntimeException runtimeException) {

                log.error("Falha não prevista ao processar a solicitação {}", idSolicitacaoAtivacao, runtimeException);
            }
        }
    }

    @Override
    public void processar(UUID idSolicitacaoAtivacao) {

        SolicitacaoAtivacao solicitacaoAtivacao = this.reservar(idSolicitacaoAtivacao);

        if (solicitacaoAtivacao == null) {

            return;
        }

        CorrelacaoUtil.definir(solicitacaoAtivacao.getCodigoCorrelacao() == null ? idSolicitacaoAtivacao.toString() : solicitacaoAtivacao.getCodigoCorrelacao());

        try {

            this.chamarProvedor(solicitacaoAtivacao);

        } finally {

            CorrelacaoUtil.limpar();
        }
    }

    // O provedor é chamado fora de transação; o @Version garante que só uma instância reserve e grave cada solicitação.
    private SolicitacaoAtivacao reservar(UUID idSolicitacaoAtivacao) {

        SolicitacaoAtivacao solicitacaoAtivacao = solicitacaoAtivacaoRepository.findById(idSolicitacaoAtivacao).orElse(null);
        Instant agora = clock.instant();

        if (solicitacaoAtivacao == null) {

            return null;
        }

        if (solicitacaoAtivacao.isReservaExpirada(agora)) {

            log.warn("Reserva expirada na solicitação {} após {} tentativas", idSolicitacaoAtivacao, solicitacaoAtivacao.getQuantidadeTentativa());

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
