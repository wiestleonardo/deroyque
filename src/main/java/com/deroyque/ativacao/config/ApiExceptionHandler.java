package com.deroyque.ativacao.config;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessServiceException.class)
    public ResponseEntity<Object> tratarBusinessServiceException(BusinessServiceException businessServiceException,
            HttpServletRequest httpServletRequest) {

        ProblemDetail problemDetail = ProblemDetails.montar(businessServiceException.getErroEnum(), httpServletRequest.getRequestURI(),
                businessServiceException.getArgumentos());
        businessServiceException.getPropriedades().forEach(problemDetail::setProperty);

        return ResponseEntity.status(problemDetail.getStatus()).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> tratarTipoInvalido(MethodArgumentTypeMismatchException methodArgumentTypeMismatchException,
            HttpServletRequest httpServletRequest) {

        return this.responderDadosInvalidos(httpServletRequest.getRequestURI(),
                List.of(montarErroCampo(methodArgumentTypeMismatchException.getName(), "valor inválido")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> tratarExcecaoNaoPrevista(Exception exception, HttpServletRequest httpServletRequest) {

        log.error("Falha não prevista em {} {}", httpServletRequest.getMethod(), httpServletRequest.getRequestURI(), exception);

        return ResponseEntity.internalServerError().body(ProblemDetails.montar(ErroEnum.ERRO_INTERNO, httpServletRequest.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException methodArgumentNotValidException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> listaErroCampo = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> montarErroCampo(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return this.responderDadosInvalidos(adquirirCaminho(webRequest), listaErroCampo);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException handlerMethodValidationException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        List<Map<String, String>> listaErroCampo = handlerMethodValidationException.getParameterValidationResults().stream()
                .flatMap(parameterValidationResult -> parameterValidationResult.getResolvableErrors().stream()
                        .map(resolvableError -> montarErroCampo(parameterValidationResult.getMethodParameter().getParameterName(),
                                resolvableError.getDefaultMessage())))
                .toList();

        return this.responderDadosInvalidos(adquirirCaminho(webRequest), listaErroCampo);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException httpMessageNotReadableException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        return this.responder(ErroEnum.CORPO_INVALIDO, httpHeaders, webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException noResourceFoundException, HttpHeaders httpHeaders,
            HttpStatusCode httpStatusCode, WebRequest webRequest) {

        return this.responder(ErroEnum.RECURSO_NAO_ENCONTRADO, httpHeaders, webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException httpRequestMethodNotSupportedException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        return this.responder(ErroEnum.METODO_NAO_SUPORTADO, httpHeaders, webRequest, httpRequestMethodNotSupportedException.getMethod());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException httpMediaTypeNotSupportedException,
            HttpHeaders httpHeaders, HttpStatusCode httpStatusCode, WebRequest webRequest) {

        return this.responder(ErroEnum.TIPO_CONTEUDO_NAO_SUPORTADO, httpHeaders, webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders httpHeaders,
            HttpStatusCode httpStatusCode, WebRequest webRequest) {

        if (body instanceof ProblemDetail problemDetail) {

            ProblemDetails.completar(problemDetail, adquirirCaminho(webRequest));
        }

        return super.handleExceptionInternal(exception, body, httpHeaders, httpStatusCode, webRequest);
    }

    // Mantém os headers do Spring, como o Allow do 405.
    private ResponseEntity<Object> responder(ErroEnum erroEnum, HttpHeaders httpHeaders, WebRequest webRequest, Object... argumentos) {

        return ResponseEntity.status(erroEnum.getHttpStatus()).headers(httpHeaders)
                .body(ProblemDetails.montar(erroEnum, adquirirCaminho(webRequest), argumentos));
    }

    private ResponseEntity<Object> responderDadosInvalidos(String caminho, List<Map<String, String>> listaErroCampo) {

        ProblemDetail problemDetail = ProblemDetails.montar(ErroEnum.DADOS_INVALIDOS, caminho);
        problemDetail.setProperty("errors", listaErroCampo);

        return ResponseEntity.badRequest().body(problemDetail);
    }

    private static Map<String, String> montarErroCampo(String campo, String mensagem) {

        return Map.of("field", String.valueOf(campo), "message", String.valueOf(mensagem));
    }

    private static String adquirirCaminho(WebRequest webRequest) {

        return webRequest instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest().getRequestURI() : null;
    }

}
