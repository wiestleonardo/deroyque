package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.util.ObjectUtil;
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

    private static final String PROPRIEDADE_ERROS = "errors";
    private static final String CAMPO = "field";
    private static final String MENSAGEM = "message";
    private static final String MENSAGEM_VALOR_INVALIDO = "valor inválido";

    private final ProblemDetailFactory problemDetailFactory;

    @ExceptionHandler(BusinessServiceException.class)
    public ResponseEntity<Object> tratarBusinessServiceException(BusinessServiceException businessServiceException,
            HttpServletRequest httpServletRequest) {

        ProblemDetail problemDetail = this.problemDetailFactory.montar(businessServiceException.getErroEnum(), httpServletRequest.getRequestURI(),
                businessServiceException.getArgumentos());
        businessServiceException.getPropriedades().forEach(problemDetail::setProperty);

        return ResponseEntity.status(problemDetail.getStatus()).body(problemDetail);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> tratarModificacaoConcorrente(ObjectOptimisticLockingFailureException objectOptimisticLockingFailureException,
            HttpServletRequest httpServletRequest) {

        log.warn("Conflito de concorrência na solicitação {}", objectOptimisticLockingFailureException.getIdentifier());

        return this.responder(ErroEnum.MODIFICACAO_CONCORRENTE, httpServletRequest.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> tratarTipoInvalido(MethodArgumentTypeMismatchException methodArgumentTypeMismatchException,
            HttpServletRequest httpServletRequest) {

        return this.responder(ErroEnum.DADOS_INVALIDOS, httpServletRequest.getRequestURI(),
                List.of(this.montarErroCampo(methodArgumentTypeMismatchException.getName(), MENSAGEM_VALOR_INVALIDO)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> tratarExcecaoNaoPrevista(Exception exception, HttpServletRequest httpServletRequest) {

        log.error("Falha não prevista em {} {}", httpServletRequest.getMethod(), httpServletRequest.getRequestURI(), exception);

        return this.responder(ErroEnum.ERRO_INTERNO, httpServletRequest.getRequestURI(), null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException methodArgumentNotValidException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> listaErroCampo = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> this.montarErroCampo(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return this.responder(ErroEnum.DADOS_INVALIDOS, this.adquirirCaminho(webRequest), listaErroCampo);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException handlerMethodValidationException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> listaErroCampo = handlerMethodValidationException.getParameterValidationResults().stream()
                .flatMap(parameterValidationResult -> parameterValidationResult.getResolvableErrors().stream()
                        .map(resolvableError -> this.montarErroCampo(parameterValidationResult.getMethodParameter().getParameterName(),
                                resolvableError.getDefaultMessage())))
                .toList();

        return this.responder(ErroEnum.DADOS_INVALIDOS, this.adquirirCaminho(webRequest), listaErroCampo);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders httpHeaders,
            HttpStatusCode httpStatusCode, WebRequest webRequest) {

        if (body instanceof ProblemDetail problemDetail) {

            this.problemDetailFactory.completar(problemDetail, this.adquirirCaminho(webRequest));
        }

        return super.handleExceptionInternal(exception, body, httpHeaders, httpStatusCode, webRequest);
    }

    private ResponseEntity<Object> responder(ErroEnum erroEnum, String caminho, List<Map<String, String>> listaErroCampo) {

        ProblemDetail problemDetail = this.problemDetailFactory.montar(erroEnum, caminho);

        if (ObjectUtil.isNotNull(listaErroCampo)) {

            problemDetail.setProperty(PROPRIEDADE_ERROS, listaErroCampo);
        }

        return ResponseEntity.status(erroEnum.getHttpStatus()).body(problemDetail);
    }

    private Map<String, String> montarErroCampo(String campo, String mensagem) {

        return Map.of(CAMPO, String.valueOf(campo), MENSAGEM, String.valueOf(mensagem));
    }

    private String adquirirCaminho(WebRequest webRequest) {

        return webRequest instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest().getRequestURI() : null;
    }

}
