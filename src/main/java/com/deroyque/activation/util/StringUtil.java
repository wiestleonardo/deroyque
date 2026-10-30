package com.deroyque.activation.util;

public final class StringUtil {

    private StringUtil() {

    }

    public static Boolean isNullOrEmpty(String text) {

        return text == null || text.isBlank();
    }

    public static Boolean notEmpty(String text) {

        return !isNullOrEmpty(text);
    }

}
