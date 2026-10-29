package com.deroyque.ativacao.vo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PoliticaRetentativaVOTest {

    private static final Duration ESPERA_INICIAL = Duration.ofSeconds(2);
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(5);

    private final PoliticaRetentativaVO politicaRetentativaVO = new PoliticaRetentativaVO(ESPERA_INICIAL, ESPERA_MAXIMA, new Random(42));

    @Test
    void deveCrescerExponencialmenteComVariacaoQuandoNaoHouverEsperaSolicitada() {

        for (int quantidadeTentativa = 1; quantidadeTentativa <= 6; quantidadeTentativa++) {

            Duration esperaCompleta = ESPERA_INICIAL.multipliedBy(1L << (quantidadeTentativa - 1));

            Duration espera = this.politicaRetentativaVO.calcularEspera(quantidadeTentativa, null);

            assertThat(espera).isBetween(esperaCompleta.dividedBy(2), esperaCompleta);
        }
    }

    @Test
    void deveLimitarNaEsperaMaximaQuandoTentativasForemMuitas() {

        assertThat(this.politicaRetentativaVO.calcularEspera(50, null)).isLessThanOrEqualTo(ESPERA_MAXIMA);
    }

    @Test
    void deveUsarEsperaDoProvedorQuandoRetryAfterForInformado() {

        assertThat(this.politicaRetentativaVO.calcularEspera(1, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void deveLimitarNaEsperaMaximaQuandoRetryAfterForMaior() {

        assertThat(this.politicaRetentativaVO.calcularEspera(1, Duration.ofHours(2))).isEqualTo(ESPERA_MAXIMA);
    }

}
