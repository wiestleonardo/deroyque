package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.repository.ActivationRequestRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    private static final String REQUESTS_METRIC = "activation.requests";
    private static final String STATUS_TAG = "status";

    @Bean
    public MeterBinder requestsByStatusMetric(ActivationRequestRepository activationRequestRepository) {

        return meterRegistry -> {

            for (ActivationStatusEnum activationStatusEnum : ActivationStatusEnum.values()) {

                Gauge.builder(REQUESTS_METRIC, activationRequestRepository, repository -> repository.countByStatus(activationStatusEnum))
                        .tag(STATUS_TAG, activationStatusEnum.name())
                        .description("Activation requests by status")
                        .register(meterRegistry);
            }
        };
    }

}
