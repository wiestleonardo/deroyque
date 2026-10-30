package com.deroyque.ativacao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RelogioConfig {

    @Bean
    public Clock clock() {

        return Clock.systemDefaultZone();
    }

}
