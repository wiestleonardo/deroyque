package com.deroyque.ativacao.localdev;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "local-dev")
public record LocalDevProperties(

        @DefaultValue("false")
        Boolean habilitado,

        String segredoJwt,

        @DefaultValue("http://localhost/local-dev")
        String emissor) {

    @Override
    public String toString() {

        return "LocalDevProperties[habilitado=%s, emissor=%s, segredoJwt=***]".formatted(this.habilitado, this.emissor);
    }

}
