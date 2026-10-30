package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.intercomm.client.ProvedorAtivacaoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    public ProvedorAtivacaoClient provedorAtivacaoClient(RestClient.Builder restClientBuilder, IntegracaoErrorHandler integracaoErrorHandler,
            ProvedorAtivacaoProperties provedorAtivacaoProperties) {

        SimpleClientHttpRequestFactory simpleClientHttpRequestFactory = new SimpleClientHttpRequestFactory();
        simpleClientHttpRequestFactory.setConnectTimeout(provedorAtivacaoProperties.tempoLimiteConexao());
        simpleClientHttpRequestFactory.setReadTimeout(provedorAtivacaoProperties.tempoLimiteLeitura());

        RestClient.Builder builder = integracaoErrorHandler.aplicar(restClientBuilder.clone())
                .baseUrl(provedorAtivacaoProperties.urlBase())
                .requestFactory(simpleClientHttpRequestFactory);

        if (provedorAtivacaoProperties.chaveApi() != null && !provedorAtivacaoProperties.chaveApi().isBlank()) {

            builder.defaultHeader(HeaderConstants.CHAVE_API, provedorAtivacaoProperties.chaveApi());
        }

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(ProvedorAtivacaoClient.class);
    }

}
