package com.deroyque.activation.infrastructure.web;

import com.deroyque.activation.api.ProblemFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejeita corpos acima do limite antes de chegar ao Jackson. O corpo esperado tem ~150 bytes;
 * um limite baixo reduz o custo de requisições abusivas. (Requisições sem Content-Length ficam a
 * cargo do gateway/proxy, ver README.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(@Value("${app.http.max-request-size:16KB}") DataSize maxSize,
                                  ObjectMapper objectMapper) {
        this.maxBytes = maxSize.toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            var problem = ProblemFactory.create(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large",
                    "Requisição muito grande", "O corpo da requisição excede " + maxBytes + " bytes.",
                    request.getRequestURI());
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        chain.doFilter(request, response);
    }
}
