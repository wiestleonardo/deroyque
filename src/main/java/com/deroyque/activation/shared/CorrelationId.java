package com.deroyque.activation.shared;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Identificador de correlação propagado da requisição HTTP até a chamada ao provedor (via MDC). */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private CorrelationId() {
    }

    /** Aceita o valor recebido apenas se for seguro (evita log injection); senão gera um novo. */
    public static String sanitizeOrGenerate(String candidate) {
        if (candidate != null && VALID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
