package com.deroyque.activation.infrastructure.config;

import com.deroyque.activation.application.ProcessingProperties;
import com.deroyque.activation.application.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Random;

@Configuration
@EnableScheduling
public class ProcessingConfig {

    @Bean
    RetryPolicy retryPolicy(ProcessingProperties properties) {
        // Random é thread-safe; o worker processa o lote em várias threads.
        return new RetryPolicy(properties.initialBackoff(), properties.maxBackoff(), new Random());
    }
}
