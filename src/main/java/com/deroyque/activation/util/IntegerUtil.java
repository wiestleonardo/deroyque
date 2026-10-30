package com.deroyque.activation.util;

public final class IntegerUtil {

    private IntegerUtil() {

    }

    public static final Integer ZERO = 0;
    public static final Integer ONE = 1;

    public static Integer sum(Integer value, Integer otherValue) {

        return Math.addExact(valueOrZero(value), valueOrZero(otherValue));
    }

    public static Boolean greaterOrEqual(Integer value, Integer otherValue) {

        return valueOrZero(value) >= valueOrZero(otherValue);
    }

    private static Integer valueOrZero(Integer value) {

        return value == null ? ZERO : value;
    }

}
