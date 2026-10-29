package com.deroyque.ativacao.util;

public final class IntegerUtil {

    private IntegerUtil() {

    }

    public static final Integer ZERO = 0;
    public static final Integer UM = 1;

    public static Integer soma(Integer valor, Integer outroValor) {

        return Math.addExact(valorOuZero(valor), valorOuZero(outroValor));
    }

    public static Boolean maiorOuIgual(Integer valor, Integer outroValor) {

        return valorOuZero(valor) >= valorOuZero(outroValor);
    }

    private static Integer valorOuZero(Integer valor) {

        return valor == null ? ZERO : valor;
    }

}
