package com.deroyque.ativacao.config;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.util.CorrelacaoUtil;
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
public class CorrelacaoFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {

        String codigoCorrelacao = CorrelacaoUtil.validarOuGerar(httpServletRequest.getHeader(HeaderConstants.CORRELACAO));

        CorrelacaoUtil.definir(codigoCorrelacao);
        httpServletResponse.setHeader(HeaderConstants.CORRELACAO, codigoCorrelacao);

        try {

            filterChain.doFilter(httpServletRequest, httpServletResponse);

        } finally {

            CorrelacaoUtil.limpar();
        }
    }

}
