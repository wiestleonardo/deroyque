package com.deroyque.activation.config;

import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.constants.SecurityConstants;
import com.deroyque.activation.enumerator.ErrorEnum;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity, ObjectMapper objectMapper, ProblemDetailFactory problemDetailFactory,
            @Value("${local-dev.enabled:false}") Boolean localDevEnabled) throws Exception {

        BearerTokenAuthenticationEntryPoint bearerTokenAuthenticationEntryPoint = new BearerTokenAuthenticationEntryPoint();
        BearerTokenAccessDeniedHandler bearerTokenAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizeHttpRequests -> {

                    authorizeHttpRequests
                            .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class, PrometheusScrapeEndpoint.class)).permitAll()
                            .requestMatchers("/error").permitAll();

                    if (localDevEnabled) {

                        authorizeHttpRequests.requestMatchers(RouteConstants.LocalDev.BASE + "/**", RouteConstants.ActivationProvider.BASE + "/**")
                                .permitAll();
                    }

                    authorizeHttpRequests
                            .requestMatchers(HttpMethod.POST, RouteConstants.ActivationRequest.BASE).hasAuthority(SecurityConstants.AUTHORITY_WRITE)
                            .requestMatchers(HttpMethod.GET, RouteConstants.ActivationRequest.BASE, RouteConstants.ActivationRequest.BASE + "/**")
                            .hasAuthority(SecurityConstants.AUTHORITY_READ)
                            .anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2ResourceServer -> oauth2ResourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((httpServletRequest, httpServletResponse, authenticationException) -> {

                            bearerTokenAuthenticationEntryPoint.commence(httpServletRequest, httpServletResponse, authenticationException);
                            writeError(objectMapper, problemDetailFactory, httpServletRequest, httpServletResponse, ErrorEnum.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((httpServletRequest, httpServletResponse, accessDeniedException) -> {

                            bearerTokenAccessDeniedHandler.handle(httpServletRequest, httpServletResponse, accessDeniedException);
                            writeError(objectMapper, problemDetailFactory, httpServletRequest, httpServletResponse, ErrorEnum.FORBIDDEN);
                        }))
                .build();
    }

    public static void writeError(ObjectMapper objectMapper, ProblemDetailFactory problemDetailFactory, HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse, ErrorEnum errorEnum) throws IOException {

        ProblemDetail problemDetail = problemDetailFactory.create(errorEnum, httpServletRequest.getRequestURI());

        httpServletResponse.setStatus(errorEnum.getHttpStatus().value());
        httpServletResponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(httpServletResponse.getOutputStream(), problemDetail);
    }

}
