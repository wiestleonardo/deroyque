package com.deroyque.ativacao.support;

import com.deroyque.ativacao.constants.SegurancaConstants;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TokenTeste {

    private TokenTeste() {

    }

    public static final String SISTEMA_ORIGEM = "system-a";

    public static JwtRequestPostProcessor escrita() {

        return jwt().jwt(jwt -> jwt.claim("client_id", SISTEMA_ORIGEM)).authorities(new SimpleGrantedAuthority(SegurancaConstants.AUTORIDADE_ESCRITA));
    }

    public static JwtRequestPostProcessor leitura() {

        return jwt().jwt(jwt -> jwt.claim("client_id", SISTEMA_ORIGEM)).authorities(new SimpleGrantedAuthority(SegurancaConstants.AUTORIDADE_LEITURA));
    }

}
