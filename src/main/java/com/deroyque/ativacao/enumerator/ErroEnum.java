package com.deroyque.ativacao.enumerator;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErroEnum {

    DADOS_INVALIDOS("validation-error", HttpStatus.BAD_REQUEST, "Dados inválidos", "Um ou mais campos não passaram na validação."),
    CORPO_INVALIDO("validation-error", HttpStatus.BAD_REQUEST, "Dados inválidos", "O corpo da requisição não é um JSON válido."),
    RECURSO_NAO_ENCONTRADO("resource-not-found", HttpStatus.NOT_FOUND, "Recurso não encontrado", "O caminho informado não existe."),
    METODO_NAO_SUPORTADO("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Método não suportado", "O método %s não é aceito neste caminho."),
    TIPO_CONTEUDO_NAO_SUPORTADO("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado",
            "Envie o corpo como application/json."),
    SOLICITACAO_NAO_ENCONTRADA("not-found", HttpStatus.NOT_FOUND, "Solicitação não encontrada", "Nenhuma solicitação encontrada com o id informado."),
    SOLICITACAO_DUPLICADA("duplicate-request", HttpStatus.CONFLICT, "Solicitação duplicada",
            "O externalId '%s' já foi usado com outro customerId ou productCode."),
    TRANSICAO_SITUACAO_INVALIDA("invalid-status-transition", HttpStatus.CONFLICT, "Transição de status inválida", "Não é possível mudar o status de %s para %s."),
    NAO_AUTENTICADO("unauthorized", HttpStatus.UNAUTHORIZED, "Não autenticado", "Token ausente, inválido ou expirado."),
    ACESSO_NEGADO("forbidden", HttpStatus.FORBIDDEN, "Acesso negado", "O token não possui o escopo necessário para esta operação."),
    ERRO_INTERNO("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Ocorreu um erro inesperado. Informe o traceId ao suporte.");

    private final String codigo;

    private final HttpStatus httpStatus;

    private final String titulo;

    private final String detalhe;

}
