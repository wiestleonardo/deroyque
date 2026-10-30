package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class ProblemDetails {

    private ProblemDetails() {

    }

    private static final String URI_BASE_TIPO = "https://api.exemplo.com/problems/";

    public static ProblemDetail montar(ErroEnum erroEnum, String caminho, Object... argumentos) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(erroEnum.getHttpStatus(), erroEnum.getDetalhe().formatted(argumentos));
        problemDetail.setType(URI.create(URI_BASE_TIPO + erroEnum.getCodigo()));
        problemDetail.setTitle(erroEnum.getTitulo());

        completar(problemDetail, caminho);

        return problemDetail;
    }

    public static void completar(ProblemDetail problemDetail, String caminho) {

        if (problemDetail.getInstance() == null && caminho != null) {

            problemDetail.setInstance(URI.create(caminho));
        }

        if (CorrelacaoUtil.adquirirAtual() != null) {

            problemDetail.setProperty("traceId", CorrelacaoUtil.adquirirAtual());
        }
    }

}
