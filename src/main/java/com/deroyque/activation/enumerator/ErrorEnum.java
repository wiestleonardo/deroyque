package com.deroyque.activation.enumerator;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorEnum {

    VALIDATION_ERROR("validation-error", "error.validation", HttpStatus.BAD_REQUEST),
    INVALID_STATUS("validation-error", "error.invalid-status", HttpStatus.BAD_REQUEST),
    REQUEST_NOT_FOUND("not-found", "error.request-not-found", HttpStatus.NOT_FOUND),
    DUPLICATE_REQUEST("duplicate-request", "error.duplicate-request", HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION("concurrent-modification", "error.concurrent-modification", HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION("invalid-status-transition", "error.invalid-status-transition", HttpStatus.CONFLICT),
    UNAUTHORIZED("unauthorized", "error.unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", "error.forbidden", HttpStatus.FORBIDDEN),
    PAYLOAD_TOO_LARGE("payload-too-large", "error.payload-too-large", HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_ERROR("internal-error", "error.internal", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;

    private final String messageKey;

    private final HttpStatus httpStatus;

}
