package com.deroyque.activation.enumerator;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorEnum {

    VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST, "Invalid data", "One or more fields are invalid."),
    REQUEST_NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Request not found", "No activation request was found with this id."),
    DUPLICATE_REQUEST("duplicate-request", HttpStatus.CONFLICT, "Duplicate request",
            "The externalId '%s' was already used with a different customerId or productCode."),
    INVALID_STATUS_TRANSITION("invalid-status-transition", HttpStatus.CONFLICT, "Invalid status transition", "Cannot change status from %s to %s."),
    UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Unauthorized", "Missing, invalid or expired token."),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden", "The token does not have the scope required for this operation."),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred. Please send the traceId to support.");

    private final String code;

    private final HttpStatus httpStatus;

    private final String title;

    private final String detail;

}
