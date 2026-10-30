package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.util.ObjectUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
@RequiredArgsConstructor
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERRORS_PROPERTY = "errors";
    private static final String FIELD = "field";
    private static final String MESSAGE = "message";
    private static final String INVALID_VALUE_MESSAGE = "invalid value";

    private final ProblemDetailFactory problemDetailFactory;

    @ExceptionHandler(BusinessServiceException.class)
    public ResponseEntity<Object> handleBusinessServiceException(BusinessServiceException businessServiceException,
            HttpServletRequest httpServletRequest) {

        ProblemDetail problemDetail = this.problemDetailFactory.create(businessServiceException.getErrorEnum(), httpServletRequest.getRequestURI(),
                businessServiceException.getArguments());
        businessServiceException.getProperties().forEach(problemDetail::setProperty);

        return ResponseEntity.status(problemDetail.getStatus()).body(problemDetail);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleConcurrentModification(ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException,
            HttpServletRequest httpServletRequest) {

        log.warn("Concurrent modification on activation request {}", objectOptimisticLockingFailureException.getIdentifier());

        return this.respond(ErrorEnum.CONCURRENT_MODIFICATION, httpServletRequest.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException methodArgumentTypeMismatchException,
            HttpServletRequest httpServletRequest) {

        return this.respond(ErrorEnum.VALIDATION_ERROR, httpServletRequest.getRequestURI(),
                List.of(this.createFieldError(methodArgumentTypeMismatchException.getName(), INVALID_VALUE_MESSAGE)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, HttpServletRequest httpServletRequest) {

        log.error("Unexpected failure on {} {}", httpServletRequest.getMethod(), httpServletRequest.getRequestURI(), exception);

        return this.respond(ErrorEnum.INTERNAL_ERROR, httpServletRequest.getRequestURI(), null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException methodArgumentNotValidException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> fieldErrorList = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> this.createFieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return this.respond(ErrorEnum.VALIDATION_ERROR, this.getPath(webRequest), fieldErrorList);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException handlerMethodValidationException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> fieldErrorList = handlerMethodValidationException.getParameterValidationResults().stream()
                .flatMap(parameterValidationResult -> parameterValidationResult.getResolvableErrors().stream()
                        .map(resolvableError -> this.createFieldError(parameterValidationResult.getMethodParameter().getParameterName(),
                                resolvableError.getDefaultMessage())))
                .toList();

        return this.respond(ErrorEnum.VALIDATION_ERROR, this.getPath(webRequest), fieldErrorList);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders httpHeaders,
            HttpStatusCode httpStatusCode, WebRequest webRequest) {

        if (body instanceof ProblemDetail problemDetail) {

            this.problemDetailFactory.complete(problemDetail, this.getPath(webRequest));
        }

        return super.handleExceptionInternal(exception, body, httpHeaders, httpStatusCode, webRequest);
    }

    private ResponseEntity<Object> respond(ErrorEnum errorEnum, String path, List<Map<String, String>> fieldErrorList) {

        ProblemDetail problemDetail = this.problemDetailFactory.create(errorEnum, path);

        if (ObjectUtil.isNotNull(fieldErrorList)) {

            problemDetail.setProperty(ERRORS_PROPERTY, fieldErrorList);
        }

        return ResponseEntity.status(errorEnum.getHttpStatus()).body(problemDetail);
    }

    private Map<String, String> createFieldError(String field, String message) {

        return Map.of(FIELD, String.valueOf(field), MESSAGE, String.valueOf(message));
    }

    private String getPath(WebRequest webRequest) {

        return webRequest instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest().getRequestURI() : null;
    }

}
