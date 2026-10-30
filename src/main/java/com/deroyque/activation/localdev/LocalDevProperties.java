package com.deroyque.activation.localdev;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "local-dev")
public record LocalDevProperties(

        @DefaultValue("false")
        Boolean enabled,

        String jwtSecret,

        @DefaultValue("http://localhost/local-dev")
        String issuer) {

    @Override
    public String toString() {

        return "LocalDevProperties[enabled=%s, issuer=%s, jwtSecret=***]".formatted(this.enabled, this.issuer);
    }

}
