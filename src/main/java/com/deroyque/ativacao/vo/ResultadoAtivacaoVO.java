package com.deroyque.ativacao.vo;

import java.time.Duration;

public sealed interface ResultadoAtivacaoVO {

    public record Sucesso() implements ResultadoAtivacaoVO {

    }

    public record FalhaDefinitiva(String motivo) implements ResultadoAtivacaoVO {

    }

    public record FalhaTemporaria(String motivo, Duration esperaSolicitada) implements ResultadoAtivacaoVO {

        public FalhaTemporaria(String motivo) {

            this(motivo, null);
        }

    }

}
