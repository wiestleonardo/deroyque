package com.deroyque.activation.config;

import com.deroyque.activation.constants.HeaderConstants;
import com.deroyque.activation.util.CorrelationUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = CorrelationUtil.validateOrGenerate(httpServletRequest.getHeader(HeaderConstants.CORRELATION_ID));

        CorrelationUtil.set(correlationId);
        httpServletResponse.setHeader(HeaderConstants.CORRELATION_ID, correlationId);

        try {

            filterChain.doFilter(httpServletRequest, httpServletResponse);

        } finally {

            CorrelationUtil.clear();
        }
    }

}
