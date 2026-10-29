package com.deroyque.activation.api;

import com.deroyque.activation.application.DuplicateRequestConflictException;
import com.deroyque.activation.application.RequestNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Converte exceções em Problem Details (RFC 9457). Nunca devolve stack trace nem mensagem interna:
 * o detalhe técnico vai para o log, ligado à resposta pelo traceId.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RequestNotFoundException.class)
    ProblemDetail handleNotFound(RequestNotFoundException ex, HttpServletRequest request) {
        return ProblemFactory.create(HttpStatus.NOT_FOUND, "not-found", "Solicitação não encontrada",
                "Nenhuma solicitação encontrada com o id informado.", request.getRequestURI());
    }

    @ExceptionHandler(DuplicateRequestConflictException.class)
    ProblemDetail handleDuplicate(DuplicateRequestConflictException ex, HttpServletRequest request) {
        var problem = ProblemFactory.create(HttpStatus.CONFLICT, "duplicate-request", "Solicitação duplicada",
                "O externalId '%s' já foi usado em outra solicitação com customerId/productCode diferentes."
                        .formatted(ex.getExternalId()),
                request.getRequestURI());
        problem.setProperty("existingRequestId", ex.getExistingId());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Conflito de concorrência: {}", ex.getMessage());
        return ProblemFactory.create(HttpStatus.CONFLICT, "concurrent-modification", "Conflito de concorrência",
                "A solicitação foi alterada por outro processo. Tente novamente.", request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        var problem = ProblemFactory.create(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
                "Um ou mais parâmetros não passaram na validação.", request.getRequestURI());
        problem.setProperty("errors", List.of(Map.of("field", ex.getName(), "message", "valor inválido")));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemFactory.create(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var problem = ProblemFactory.create(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
                "Um ou mais campos não passaram na validação.", path(request));
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        var problem = ProblemFactory.create(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
                "Um ou mais parâmetros não passaram na validação.", path(request));
        var errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Demais exceções do Spring MVC (JSON malformado, 405, 415...) ganham o mesmo formato. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemFactory.enrich(problem);
            if (problem.getInstance() == null && path(request) != null) {
                problem.setInstance(URI.create(path(request)));
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
