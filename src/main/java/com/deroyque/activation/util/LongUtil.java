package com.deroyque.activation.util;

public final class LongUtil {

    private LongUtil() {

    }

    public static Long toLong(String text) {

        if (StringUtil.isNullOrEmpty(text)) {

            return null;
        }

        try {

            return Long.valueOf(text.trim());

        } catch (NumberFormatException numberFormatException) {

            return null;
        }
    }

}
