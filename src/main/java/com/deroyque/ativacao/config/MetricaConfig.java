package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.repository.SolicitacaoAtivacaoRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricaConfig {

    private static final String METRICA_SOLICITACOES = "activation.requests";
    private static final String TAG_SITUACAO = "status";

    @Bean
    public MeterBinder metricaSolicitacaoPorSituacao(SolicitacaoAtivacaoRepository solicitacaoAtivacaoRepository) {

        return meterRegistry -> {

            for (SituacaoSolicitacaoAtivacaoEnum situacaoSolicitacaoAtivacaoEnum : SituacaoSolicitacaoAtivacaoEnum.values()) {

                Gauge.builder(METRICA_SOLICITACOES, solicitacaoAtivacaoRepository,
                                repository -> repository.countBySituacao(situacaoSolicitacaoAtivacaoEnum))
                        .tag(TAG_SITUACAO, situacaoSolicitacaoAtivacaoEnum.getCodigo())
                        .description("Solicitações por status")
                        .register(meterRegistry);
            }
        };
    }

}
