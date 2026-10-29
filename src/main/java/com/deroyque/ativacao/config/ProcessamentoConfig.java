package com.deroyque.ativacao.config;

import com.deroyque.ativacao.vo.PoliticaRetentativaVO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

@Configuration(proxyBeanMethods = false)
public class ProcessamentoConfig {

    @Bean
    public PoliticaRetentativaVO politicaRetentativaVO(ProcessamentoProperties processamentoProperties) {

        return new PoliticaRetentativaVO(processamentoProperties.esperaInicial(), processamentoProperties.esperaMaxima(), new Random());
    }

}
