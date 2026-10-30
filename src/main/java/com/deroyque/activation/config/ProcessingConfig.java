package com.deroyque.activation.config;

import com.deroyque.activation.vo.RetryPolicyVO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

@Configuration(proxyBeanMethods = false)
public class ProcessingConfig {

    @Bean
    public RetryPolicyVO retryPolicyVO(ProcessingProperties processingProperties) {

        return new RetryPolicyVO(processingProperties.initialBackoff(), processingProperties.maxBackoff(), new Random());
    }

}
