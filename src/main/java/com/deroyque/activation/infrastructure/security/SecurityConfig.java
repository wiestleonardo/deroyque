package com.deroyque.activation.infrastructure.security;

import com.deroyque.activation.api.ProblemFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * Resource Server OAuth 2.0: o serviço só valida tokens JWT emitidos pelo servidor de autorização.
 * Escopos: {@code activation:write} cria; {@code activation:read} consulta.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String SCOPE_READ = "SCOPE_activation:read";
    public static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                            @Value("${app.local-dev.enabled:false}") boolean localDev)
            throws Exception {
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        var deniedHandler = new BearerTokenAccessDeniedHandler();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
                            .permitAll();
                    if (localDev) {
                        // Somente no perfil local: emissor de tokens de teste e provedor simulado.
                        auth.requestMatchers("/dev/**", "/provider/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                            .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**")
                            .hasAuthority(SCOPE_READ)
                            .anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, ex) -> {
                            entryPoint.commence(request, response, ex); // mantém o header WWW-Authenticate
                            writeProblem(objectMapper, request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                                    "Não autenticado", "Token ausente, inválido ou expirado.");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            deniedHandler.handle(request, response, ex);
                            writeProblem(objectMapper, request, response, HttpStatus.FORBIDDEN, "forbidden",
                                    "Acesso negado", "O token não possui o escopo necessário para esta operação.");
                        }));
        return http.build();
    }

    private static void writeProblem(ObjectMapper objectMapper, HttpServletRequest request,
                                     HttpServletResponse response, HttpStatus status, String type, String title,
                                     String detail) throws IOException {
        var problem = ProblemFactory.create(status, type, title, detail, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
