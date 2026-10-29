package com.deroyque.ativacao.util;

import java.util.Objects;

public final class ObjectUtil {

    private ObjectUtil() {

    }

    public static Boolean isNull(Object objeto) {

        return objeto == null;
    }

    public static Boolean isNotNull(Object objeto) {

        return objeto != null;
    }

    public static Boolean isEquals(Object objeto, Object outroObjeto) {

        return Objects.equals(objeto, outroObjeto);
    }

}
