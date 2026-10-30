package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {

    private final Long maxSizeBytes;

    private final ObjectMapper objectMapper;

    private final ProblemDetailFactory problemDetailFactory;

    public RequestSizeFilter(@Value("${http.max-request-size}") DataSize maxSize, ObjectMapper objectMapper, ProblemDetailFactory problemDetailFactory) {

        this.maxSizeBytes = maxSize.toBytes();
        this.objectMapper = objectMapper;
        this.problemDetailFactory = problemDetailFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {

        if (httpServletRequest.getContentLengthLong() > this.maxSizeBytes) {

            SecurityConfig.writeError(this.objectMapper, this.problemDetailFactory, httpServletRequest, httpServletResponse, ErrorEnum.PAYLOAD_TOO_LARGE);
            return;
        }

        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }

}
