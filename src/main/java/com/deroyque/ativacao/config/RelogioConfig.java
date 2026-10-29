package com.deroyque.ativacao.config;

import com.deroyque.ativacao.util.DateUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RelogioConfig {

    @Bean
    public Clock clock() {

        return Clock.system(DateUtil.FUSO_BRASILIA);
    }

}
