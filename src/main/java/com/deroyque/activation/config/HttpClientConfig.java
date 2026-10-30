package com.deroyque.activation.config;

import com.deroyque.activation.constants.HeaderConstants;
import com.deroyque.activation.intercomm.client.ActivationProviderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    public ActivationProviderClient activationProviderClient(RestClient.Builder restClientBuilder, IntegrationErrorHandler integrationErrorHandler,
            ActivationProviderProperties activationProviderProperties) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(activationProviderProperties.connectTimeout());
        requestFactory.setReadTimeout(activationProviderProperties.readTimeout());

        RestClient.Builder builder = integrationErrorHandler.apply(restClientBuilder.clone())
                .baseUrl(activationProviderProperties.baseUrl())
                .requestFactory(requestFactory);

        if (activationProviderProperties.apiKey() != null && !activationProviderProperties.apiKey().isBlank()) {

            builder.defaultHeader(HeaderConstants.API_KEY, activationProviderProperties.apiKey());
        }

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(ActivationProviderClient.class);
    }

}
