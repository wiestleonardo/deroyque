package com.deroyque.activation.config;

import com.deroyque.activation.constants.HeaderConstants;
import com.deroyque.activation.intercomm.client.ActivationProviderClient;
import com.deroyque.activation.util.StringUtil;
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
    public ActivationProviderClient activationProviderClient(RestClient.Builder restClientBuilder,
            ActivationProviderProperties activationProviderProperties) {

        // HTTP/1.1 is forced because the JDK HttpClient h2c upgrade fails on servers that only speak HTTP/1.1.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(activationProviderProperties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory jdkClientHttpRequestFactory = new JdkClientHttpRequestFactory(httpClient);
        jdkClientHttpRequestFactory.setReadTimeout(activationProviderProperties.readTimeout());

        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(activationProviderProperties.baseUrl())
                .requestFactory(jdkClientHttpRequestFactory);

        if (StringUtil.notEmpty(activationProviderProperties.apiKey())) {

            builder.defaultHeader(HeaderConstants.API_KEY, activationProviderProperties.apiKey());
        }

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(ActivationProviderClient.class);
    }

}
