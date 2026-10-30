package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.RotaConstants;
import com.deroyque.ativacao.constants.SegurancaConstants;
import com.deroyque.ativacao.enumerator.ErroEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity, ObjectMapper objectMapper,
            @Value("${local-dev.habilitado:false}") Boolean localDevHabilitado) throws Exception {

        BearerTokenAuthenticationEntryPoint bearerTokenAuthenticationEntryPoint = new BearerTokenAuthenticationEntryPoint();
        BearerTokenAccessDeniedHandler bearerTokenAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizeHttpRequests -> {

                    authorizeHttpRequests
                            .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class, PrometheusScrapeEndpoint.class)).permitAll()
                            .requestMatchers("/error").permitAll();

                    if (localDevHabilitado) {

                        authorizeHttpRequests.requestMatchers(RotaConstants.LocalDev.BASE + "/**").permitAll();
                    }

                    authorizeHttpRequests
                            .requestMatchers(HttpMethod.POST, RotaConstants.SolicitacaoAtivacao.BASE).hasAuthority(SegurancaConstants.AUTORIDADE_ESCRITA)
                            .requestMatchers(HttpMethod.GET, RotaConstants.SolicitacaoAtivacao.BASE, RotaConstants.SolicitacaoAtivacao.BASE + "/**")
                            .hasAuthority(SegurancaConstants.AUTORIDADE_LEITURA)
                            .anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2ResourceServer -> oauth2ResourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((httpServletRequest, httpServletResponse, authenticationException) -> {

                            bearerTokenAuthenticationEntryPoint.commence(httpServletRequest, httpServletResponse, authenticationException);
                            escreverErro(objectMapper, httpServletRequest, httpServletResponse, ErroEnum.NAO_AUTENTICADO);
                        })
                        .accessDeniedHandler((httpServletRequest, httpServletResponse, accessDeniedException) -> {

                            bearerTokenAccessDeniedHandler.handle(httpServletRequest, httpServletResponse, accessDeniedException);
                            escreverErro(objectMapper, httpServletRequest, httpServletResponse, ErroEnum.ACESSO_NEGADO);
                        }))
                .build();
    }

    private static void escreverErro(ObjectMapper objectMapper, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
            ErroEnum erroEnum) throws IOException {

        ProblemDetail problemDetail = ProblemDetails.montar(erroEnum, httpServletRequest.getRequestURI());

        httpServletResponse.setStatus(erroEnum.getHttpStatus().value());
        httpServletResponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(httpServletResponse.getOutputStream(), problemDetail);
    }

}
