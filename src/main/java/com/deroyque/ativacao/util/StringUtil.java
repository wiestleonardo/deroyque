package com.deroyque.ativacao.util;

public final class StringUtil {

    private StringUtil() {

    }

    public static Boolean isNullOrEmpty(String texto) {

        return texto == null || texto.isBlank();
    }

    public static Boolean notEmpty(String texto) {

        return !isNullOrEmpty(texto);
    }

}
