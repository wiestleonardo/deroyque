package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.ErroEnum;
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
public class TamanhoRequisicaoFilter extends OncePerRequestFilter {

    private final Long tamanhoMaximoBytes;

    private final ObjectMapper objectMapper;

    private final ProblemDetailFactory problemDetailFactory;

    public TamanhoRequisicaoFilter(@Value("${http.tamanho-maximo-requisicao}") DataSize tamanhoMaximo, ObjectMapper objectMapper,
            ProblemDetailFactory problemDetailFactory) {

        this.tamanhoMaximoBytes = tamanhoMaximo.toBytes();
        this.objectMapper = objectMapper;
        this.problemDetailFactory = problemDetailFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {

        if (httpServletRequest.getContentLengthLong() > this.tamanhoMaximoBytes) {

            SecurityConfig.escreverErro(this.objectMapper, this.problemDetailFactory, httpServletRequest, httpServletResponse,
                    ErroEnum.REQUISICAO_MUITO_GRANDE);
            return;
        }

        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }

}
