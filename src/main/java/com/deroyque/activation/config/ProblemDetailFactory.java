package com.deroyque.activation.config;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.util.ObjectUtil;
import com.deroyque.activation.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ProblemDetailFactory {

    private static final String TYPE_BASE_URI = "https://api.exemplo.com/problems/";
    private static final String TRACE_ID_PROPERTY = "traceId";
    private static final String TITLE_SUFFIX = ".title";
    private static final String DETAIL_SUFFIX = ".detail";

    private final MessageSource messageSource;

    public ProblemDetail create(ErrorEnum errorEnum, String path, Object... arguments) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorEnum.getHttpStatus(),
                this.getMessage(errorEnum.getMessageKey() + DETAIL_SUFFIX, arguments));
        problemDetail.setType(URI.create(TYPE_BASE_URI + errorEnum.getCode()));
        problemDetail.setTitle(this.getMessage(errorEnum.getMessageKey() + TITLE_SUFFIX));

        this.complete(problemDetail, path);

        return problemDetail;
    }

    public void complete(ProblemDetail problemDetail, String path) {

        if (ObjectUtil.isNull(problemDetail.getInstance()) && StringUtil.notEmpty(path)) {

            problemDetail.setInstance(URI.create(path));
        }

        String correlationId = CorrelationUtil.getCurrent();

        if (StringUtil.notEmpty(correlationId)) {

            problemDetail.setProperty(TRACE_ID_PROPERTY, correlationId);
        }
    }

    private String getMessage(String key, Object... arguments) {

        return this.messageSource.getMessage(key, arguments, Locale.ENGLISH);
    }

}
