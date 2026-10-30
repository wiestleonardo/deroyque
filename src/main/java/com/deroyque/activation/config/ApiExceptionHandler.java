package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessServiceException.class)
    public ResponseEntity<Object> handleBusinessServiceException(BusinessServiceException businessServiceException,
            HttpServletRequest httpServletRequest) {

        ProblemDetail problemDetail = ProblemDetails.create(businessServiceException.getErrorEnum(), httpServletRequest.getRequestURI(),
                businessServiceException.getArguments());
        businessServiceException.getProperties().forEach(problemDetail::setProperty);

        return ResponseEntity.status(problemDetail.getStatus()).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException methodArgumentTypeMismatchException,
            HttpServletRequest httpServletRequest) {

        return this.validationError(httpServletRequest.getRequestURI(), List.of(fieldError(methodArgumentTypeMismatchException.getName(), "invalid value")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, HttpServletRequest httpServletRequest) {

        log.error("Unexpected failure on {} {}", httpServletRequest.getMethod(), httpServletRequest.getRequestURI(), exception);

        return ResponseEntity.internalServerError().body(ProblemDetails.create(ErrorEnum.INTERNAL_ERROR, httpServletRequest.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException methodArgumentNotValidException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> fieldErrorList = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return this.validationError(getPath(webRequest), fieldErrorList);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException handlerMethodValidationException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> fieldErrorList = handlerMethodValidationException.getParameterValidationResults().stream()
                .flatMap(parameterValidationResult -> parameterValidationResult.getResolvableErrors().stream()
                        .map(resolvableError -> fieldError(parameterValidationResult.getMethodParameter().getParameterName(),
                                resolvableError.getDefaultMessage())))
                .toList();

        return this.validationError(getPath(webRequest), fieldErrorList);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders httpHeaders,
            HttpStatusCode httpStatusCode, WebRequest webRequest) {

        if (body instanceof ProblemDetail problemDetail) {

            ProblemDetails.complete(problemDetail, getPath(webRequest));
        }

        return super.handleExceptionInternal(exception, body, httpHeaders, httpStatusCode, webRequest);
    }

    private ResponseEntity<Object> validationError(String path, List<Map<String, String>> fieldErrorList) {

        ProblemDetail problemDetail = ProblemDetails.create(ErrorEnum.VALIDATION_ERROR, path);
        problemDetail.setProperty("errors", fieldErrorList);

        return ResponseEntity.badRequest().body(problemDetail);
    }

    private static Map<String, String> fieldError(String field, String message) {

        return Map.of("field", String.valueOf(field), "message", String.valueOf(message));
    }

    private static String getPath(WebRequest webRequest) {

        return webRequest instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest().getRequestURI() : null;
    }

}
