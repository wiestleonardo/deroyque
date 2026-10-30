package com.deroyque.activation.service.impl;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedSystemServiceImplTest {

    private final AuthenticatedSystemServiceImpl authenticatedSystemServiceImpl = new AuthenticatedSystemServiceImpl();

    @AfterEach
    void clearSecurityContext() {

        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseClientIdWhenTokenHasIt() {

        authenticate(jwtBuilder().claim("client_id", "system-a").claim("azp", "other").build());

        assertThat(this.authenticatedSystemServiceImpl.getSourceSystem()).isEqualTo("system-a");
    }

    @Test
    void shouldUseAzpWhenTokenHasNoClientId() {

        authenticate(jwtBuilder().claim("azp", "system-b").build());

        assertThat(this.authenticatedSystemServiceImpl.getSourceSystem()).isEqualTo("system-b");
    }

    @Test
    void shouldUseSubjectWhenTokenHasNoClientClaim() {

        authenticate(jwtBuilder().build());

        assertThat(this.authenticatedSystemServiceImpl.getSourceSystem()).isEqualTo("subject-system");
    }

    @Test
    void shouldUseNameWhenAuthenticationIsNotJwt() {

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("system-c", null, List.of()));

        assertThat(this.authenticatedSystemServiceImpl.getSourceSystem()).isEqualTo("system-c");
    }

    @Test
    void shouldThrowUnauthorizedWhenThereIsNoAuthentication() {

        assertThatThrownBy(this.authenticatedSystemServiceImpl::getSourceSystem)
                .isInstanceOf(BusinessServiceException.class)
                .extracting(exception -> ((BusinessServiceException) exception).getErrorEnum())
                .isEqualTo(ErrorEnum.UNAUTHORIZED);
    }

    private static Jwt.Builder jwtBuilder() {

        return Jwt.withTokenValue("token").header("alg", "none").subject("subject-system");
    }

    private static void authenticate(Jwt jwt) {

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

}
