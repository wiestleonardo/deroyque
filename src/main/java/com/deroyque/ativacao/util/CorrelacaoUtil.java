package com.deroyque.ativacao.util;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelacaoUtil {

    private CorrelacaoUtil() {

    }

    public static final String CHAVE_MDC = "correlationId";

    private static final Pattern FORMATO_VALIDO = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    public static String validarOuGerar(String codigoCorrelacao) {

        if (StringUtil.notEmpty(codigoCorrelacao) && FORMATO_VALIDO.matcher(codigoCorrelacao).matches()) {

            return codigoCorrelacao;
        }

        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String adquirirAtual() {

        return MDC.get(CHAVE_MDC);
    }

    public static void definir(String codigoCorrelacao) {

        MDC.put(CHAVE_MDC, codigoCorrelacao);
    }

    public static void limpar() {

        MDC.remove(CHAVE_MDC);
    }

}
