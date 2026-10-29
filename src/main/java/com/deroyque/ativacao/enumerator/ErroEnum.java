package com.deroyque.ativacao.enumerator;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErroEnum {

    DADOS_INVALIDOS("validation-error", "erro.dados-invalidos", HttpStatus.BAD_REQUEST),
    SITUACAO_INVALIDA("validation-error", "erro.situacao-invalida", HttpStatus.BAD_REQUEST),
    SOLICITACAO_NAO_ENCONTRADA("not-found", "erro.solicitacao-nao-encontrada", HttpStatus.NOT_FOUND),
    SOLICITACAO_DUPLICADA("duplicate-request", "erro.solicitacao-duplicada", HttpStatus.CONFLICT),
    MODIFICACAO_CONCORRENTE("concurrent-modification", "erro.modificacao-concorrente", HttpStatus.CONFLICT),
    TRANSICAO_SITUACAO_INVALIDA("invalid-status-transition", "erro.transicao-situacao-invalida", HttpStatus.CONFLICT),
    NAO_AUTENTICADO("unauthorized", "erro.nao-autenticado", HttpStatus.UNAUTHORIZED),
    ACESSO_NEGADO("forbidden", "erro.acesso-negado", HttpStatus.FORBIDDEN),
    REQUISICAO_MUITO_GRANDE("payload-too-large", "erro.requisicao-muito-grande", HttpStatus.PAYLOAD_TOO_LARGE),
    ERRO_INTERNO("internal-error", "erro.interno", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String codigo;

    private final String chaveMensagem;

    private final HttpStatus httpStatus;

}
