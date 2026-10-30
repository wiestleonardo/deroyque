package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.intercomm.client.ProvedorAtivacaoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    // HttpClient do JDK: reaproveita conexões e não prende a thread de plataforma quando a chamada roda em thread virtual,
    // ao contrário do HttpURLConnection, que usa synchronized por dentro.
    @Bean
    public ProvedorAtivacaoClient provedorAtivacaoClient(RestClient.Builder restClientBuilder, IntegracaoErrorHandler integracaoErrorHandler,
            ProvedorAtivacaoProperties provedorAtivacaoProperties) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(provedorAtivacaoProperties.tempoLimiteConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory jdkClientHttpRequestFactory = new JdkClientHttpRequestFactory(httpClient);
        jdkClientHttpRequestFactory.setReadTimeout(provedorAtivacaoProperties.tempoLimiteLeitura());

        RestClient.Builder builder = integracaoErrorHandler.aplicar(restClientBuilder.clone())
                .baseUrl(provedorAtivacaoProperties.urlBase())
                .requestFactory(jdkClientHttpRequestFactory);

        if (provedorAtivacaoProperties.chaveApi() != null && !provedorAtivacaoProperties.chaveApi().isBlank()) {

            builder.defaultHeader(HeaderConstants.CHAVE_API, provedorAtivacaoProperties.chaveApi());
        }

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(ProvedorAtivacaoClient.class);
    }

}
