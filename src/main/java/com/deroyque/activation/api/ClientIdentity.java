package com.deroyque.activation.api;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identifica o sistema consumidor a partir do token. No fluxo client_credentials o "dono" é o
 * próprio client: usamos {@code client_id} (Keycloak/Okta), depois {@code azp} e por fim {@code sub}.
 */
final class ClientIdentity {

    private ClientIdentity() {
    }

    static String of(Jwt jwt) {
        for (var claim : new String[]{"client_id", "azp"}) {
            var value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
