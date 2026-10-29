package com.deroyque.activation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Relógio injetável: todas as datas em UTC e controláveis nos testes. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
