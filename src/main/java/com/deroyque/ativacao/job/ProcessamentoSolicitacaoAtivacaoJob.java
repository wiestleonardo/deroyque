package com.deroyque.ativacao.job;

import com.deroyque.ativacao.service.ProcessamentoSolicitacaoAtivacaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "processamento.job.habilitado", havingValue = "true")
public class ProcessamentoSolicitacaoAtivacaoJob {

    private final ProcessamentoSolicitacaoAtivacaoService processamentoSolicitacaoAtivacaoService;

    @Scheduled(fixedDelayString = "${processamento.job.intervalo}")
    public void executar() {

        processamentoSolicitacaoAtivacaoService.processarPendentes();
    }

}
