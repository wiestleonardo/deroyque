package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.intercomm.client.ProvedorAtivacaoClient;
import com.deroyque.ativacao.util.StringUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    public ProvedorAtivacaoClient provedorAtivacaoClient(RestClient.Builder restClientBuilder, ProvedorAtivacaoProperties provedorAtivacaoProperties) {

        // HTTP/1.1 fixo: o upgrade para h2c do HttpClient do JDK falha em servidores que só falam HTTP/1.1.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(provedorAtivacaoProperties.tempoLimiteConexao())
                .build();

        JdkClientHttpRequestFactory jdkClientHttpRequestFactory = new JdkClientHttpRequestFactory(httpClient);
        jdkClientHttpRequestFactory.setReadTimeout(provedorAtivacaoProperties.tempoLimiteLeitura());

        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(provedorAtivacaoProperties.urlBase())
                .requestFactory(jdkClientHttpRequestFactory);

        if (StringUtil.notEmpty(provedorAtivacaoProperties.chaveApi())) {

            builder.defaultHeader(HeaderConstants.CHAVE_API, provedorAtivacaoProperties.chaveApi());
        }

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(ProvedorAtivacaoClient.class);
    }

}
