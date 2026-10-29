package com.deroyque.ativacao.util;

public final class LongUtil {

    private LongUtil() {

    }

    public static Long toLong(String texto) {

        if (StringUtil.isNullOrEmpty(texto)) {

            return null;
        }

        try {

            return Long.valueOf(texto.trim());

        } catch (NumberFormatException numberFormatException) {

            return null;
        }
    }

}
