package com.deroyque.ativacao.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.Duration;

@Getter
public class IntegracaoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String motivo;

    private final Boolean permiteNovaTentativa;

    private final Duration esperaSolicitada;

    public IntegracaoException(String motivo, Boolean permiteNovaTentativa, Duration esperaSolicitada) {

        super(motivo);
        this.motivo = motivo;
        this.permiteNovaTentativa = permiteNovaTentativa;
        this.esperaSolicitada = esperaSolicitada;
    }

}
