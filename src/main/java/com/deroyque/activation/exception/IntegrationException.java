package com.deroyque.activation.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.Duration;

@Getter
public class IntegrationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reason;

    private final Boolean retryable;

    private final Duration retryAfter;

    public IntegrationException(String reason, Boolean retryable, Duration retryAfter) {

        super(reason);
        this.reason = reason;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

}
