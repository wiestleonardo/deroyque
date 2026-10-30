package com.deroyque.ativacao.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "intercomm.provedor-ativacao")
public record ProvedorAtivacaoProperties(

        @NotBlank
        String urlBase,

        @NotNull
        Duration tempoLimiteConexao,

        @NotNull
        Duration tempoLimiteLeitura,

        String chaveApi) {

    @Override
    public String toString() {

        return "ProvedorAtivacaoProperties[urlBase=%s, tempoLimiteConexao=%s, tempoLimiteLeitura=%s, chaveApi=***]"
                .formatted(this.urlBase, this.tempoLimiteConexao, this.tempoLimiteLeitura);
    }

}
