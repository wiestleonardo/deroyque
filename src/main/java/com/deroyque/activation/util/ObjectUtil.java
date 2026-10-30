package com.deroyque.activation.util;

import java.util.Objects;

public final class ObjectUtil {

    private ObjectUtil() {

    }

    public static Boolean isNull(Object object) {

        return object == null;
    }

    public static Boolean isNotNull(Object object) {

        return object != null;
    }

    public static Boolean isEquals(Object object, Object otherObject) {

        return Objects.equals(object, otherObject);
    }

}
