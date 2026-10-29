package com.deroyque.activation.api;

import com.deroyque.activation.shared.CorrelationId;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/** Monta respostas RFC 9457 com um formato único para toda a API (inclusive 401/403). */
public final class ProblemFactory {

    private static final String TYPE_BASE = "https://api.exemplo.com/problems/";

    private ProblemFactory() {
    }

    public static ProblemDetail create(HttpStatusCode status, String type, String title, String detail,
                                       String instance) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        enrich(problem);
        return problem;
    }

    /** Adiciona o identificador de correlação, que também está em todos os logs da requisição. */
    public static void enrich(ProblemDetail problem) {
        var correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty("traceId", correlationId);
        }
    }
}
