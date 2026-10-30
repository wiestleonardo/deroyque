package com.deroyque.ativacao.exception;

import com.deroyque.ativacao.enumerator.ErroEnum;
import lombok.Getter;

import java.io.Serial;
import java.util.Map;

@Getter
public class BusinessServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErroEnum erroEnum;

    private final transient Object[] argumentos;

    private final transient Map<String, Object> propriedades;

    public BusinessServiceException(ErroEnum erroEnum, Object... argumentos) {

        this(erroEnum, Map.of(), argumentos);
    }

    public BusinessServiceException(ErroEnum erroEnum, Map<String, Object> propriedades, Object... argumentos) {

        super(erroEnum.name());
        this.erroEnum = erroEnum;
        this.argumentos = argumentos;
        this.propriedades = propriedades;
    }

}
