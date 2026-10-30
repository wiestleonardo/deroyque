package com.deroyque.activation.exception;

import com.deroyque.activation.enumerator.ErrorEnum;
import lombok.Getter;

import java.io.Serial;
import java.util.Map;

@Getter
public class BusinessServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorEnum errorEnum;

    private final transient Object[] arguments;

    private final transient Map<String, Object> properties;

    public BusinessServiceException(ErrorEnum errorEnum, Object... arguments) {

        this(errorEnum, Map.of(), arguments);
    }

    public BusinessServiceException(ErrorEnum errorEnum, Map<String, Object> properties, Object... arguments) {

        super(errorEnum.name());
        this.errorEnum = errorEnum;
        this.arguments = arguments;
        this.properties = properties;
    }

}
