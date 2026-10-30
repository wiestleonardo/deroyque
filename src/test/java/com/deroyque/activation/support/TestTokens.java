package com.deroyque.activation.support;

import com.deroyque.activation.constants.SecurityConstants;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestTokens {

    private TestTokens() {

    }

    public static final String SOURCE_SYSTEM = "system-a";

    public static JwtRequestPostProcessor writer() {

        return jwt().jwt(jwt -> jwt.claim("client_id", SOURCE_SYSTEM)).authorities(new SimpleGrantedAuthority(SecurityConstants.AUTHORITY_WRITE));
    }

    public static JwtRequestPostProcessor reader() {

        return jwt().jwt(jwt -> jwt.claim("client_id", SOURCE_SYSTEM)).authorities(new SimpleGrantedAuthority(SecurityConstants.AUTHORITY_READ));
    }

}
