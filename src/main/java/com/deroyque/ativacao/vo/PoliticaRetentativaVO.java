package com.deroyque.ativacao.vo;

import com.deroyque.ativacao.util.ObjectUtil;

import java.time.Duration;
import java.util.random.RandomGenerator;

public record PoliticaRetentativaVO(Duration esperaInicial, Duration esperaMaxima, RandomGenerator geradorAleatorio) {

    private static final Integer EXPOENTE_MAXIMO = 30;

    public Duration calcularEspera(Integer quantidadeTentativa, Duration esperaSolicitada) {

        if (ObjectUtil.isNotNull(esperaSolicitada) && !esperaSolicitada.isNegative()) {

            return menor(esperaSolicitada, this.esperaMaxima);
        }

        int expoente = Math.clamp(quantidadeTentativa - 1L, 0, EXPOENTE_MAXIMO);
        Duration esperaExponencial = menor(this.esperaInicial.multipliedBy(1L << expoente), this.esperaMaxima);
        long metadeEspera = esperaExponencial.toMillis() / 2;
        long variacao = metadeEspera == 0 ? 0 : this.geradorAleatorio.nextLong(metadeEspera + 1);

        return Duration.ofMillis(metadeEspera + variacao);
    }

    private static Duration menor(Duration duracao, Duration outraDuracao) {

        return duracao.compareTo(outraDuracao) <= 0 ? duracao : outraDuracao;
    }

}
