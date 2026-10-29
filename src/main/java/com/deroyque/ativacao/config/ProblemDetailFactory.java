package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import com.deroyque.ativacao.util.ObjectUtil;
import com.deroyque.ativacao.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ProblemDetailFactory {

    private static final String URI_BASE_TIPO = "https://api.exemplo.com/problems/";
    private static final String PROPRIEDADE_TRACE_ID = "traceId";
    private static final String SUFIXO_TITULO = ".titulo";
    private static final String SUFIXO_DETALHE = ".detalhe";
    private static final Locale LOCALE_PT_BR = Locale.of("pt", "BR");

    private final MessageSource messageSource;

    public ProblemDetail montar(ErroEnum erroEnum, String caminho, Object... argumentos) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(erroEnum.getHttpStatus(),
                this.adquirirMensagem(erroEnum.getChaveMensagem() + SUFIXO_DETALHE, argumentos));
        problemDetail.setType(URI.create(URI_BASE_TIPO + erroEnum.getCodigo()));
        problemDetail.setTitle(this.adquirirMensagem(erroEnum.getChaveMensagem() + SUFIXO_TITULO));

        this.completar(problemDetail, caminho);

        return problemDetail;
    }

    public void completar(ProblemDetail problemDetail, String caminho) {

        if (ObjectUtil.isNull(problemDetail.getInstance()) && StringUtil.notEmpty(caminho)) {

            problemDetail.setInstance(URI.create(caminho));
        }

        String codigoCorrelacao = CorrelacaoUtil.adquirirAtual();

        if (StringUtil.notEmpty(codigoCorrelacao)) {

            problemDetail.setProperty(PROPRIEDADE_TRACE_ID, codigoCorrelacao);
        }
    }

    private String adquirirMensagem(String chave, Object... argumentos) {

        return this.messageSource.getMessage(chave, argumentos, LOCALE_PT_BR);
    }

}
