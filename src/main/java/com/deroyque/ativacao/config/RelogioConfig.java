package com.deroyque.ativacao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class RelogioConfig {

    // Em milissegundos: o banco guarda microssegundos e arredondaria os nanos, deixando a data da resposta diferente da gravada.
    @Bean
    public Clock clock() {

        return Clock.tickMillis(ZoneId.systemDefault());
    }

}
