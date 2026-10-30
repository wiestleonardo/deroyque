package com.deroyque.ativacao.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "processamento")
public record ProcessamentoProperties(

        @NotNull
        @Positive
        Integer quantidadeMaximaTentativa,

        @NotNull
        Duration tempoReserva,

        @NotNull
        Duration esperaInicial,

        @NotNull
        Duration esperaMaxima,

        @NotNull
        @Positive
        Integer tamanhoLote) {

}
