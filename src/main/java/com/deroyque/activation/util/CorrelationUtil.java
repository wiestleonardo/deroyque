package com.deroyque.activation.util;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationUtil {

    private CorrelationUtil() {

    }

    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_FORMAT = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    public static String validateOrGenerate(String correlationId) {

        if (StringUtil.notEmpty(correlationId) && VALID_FORMAT.matcher(correlationId).matches()) {

            return correlationId;
        }

        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String getCurrent() {

        return MDC.get(MDC_KEY);
    }

    public static void set(String correlationId) {

        MDC.put(MDC_KEY, correlationId);
    }

    public static void clear() {

        MDC.remove(MDC_KEY);
    }

}
