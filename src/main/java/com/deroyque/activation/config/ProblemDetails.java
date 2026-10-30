package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.util.CorrelationUtil;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class ProblemDetails {

    private ProblemDetails() {

    }

    private static final String TYPE_BASE_URI = "https://api.exemplo.com/problems/";

    public static ProblemDetail create(ErrorEnum errorEnum, String path, Object... arguments) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorEnum.getHttpStatus(), errorEnum.getDetail().formatted(arguments));
        problemDetail.setType(URI.create(TYPE_BASE_URI + errorEnum.getCode()));
        problemDetail.setTitle(errorEnum.getTitle());

        complete(problemDetail, path);

        return problemDetail;
    }

    public static void complete(ProblemDetail problemDetail, String path) {

        if (problemDetail.getInstance() == null && path != null) {

            problemDetail.setInstance(URI.create(path));
        }

        if (CorrelationUtil.getCurrent() != null) {

            problemDetail.setProperty("traceId", CorrelationUtil.getCurrent());
        }
    }

}
