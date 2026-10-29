package com.deroyque.activation.api;

import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.application.CreateActivationCommand;
import com.deroyque.activation.application.CreationResult;
import com.deroyque.activation.application.DuplicateRequestConflictException;
import com.deroyque.activation.application.RequestNotFoundException;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.infrastructure.security.SecurityConfig;
import com.deroyque.activation.infrastructure.web.CorrelationIdFilter;
import com.deroyque.activation.infrastructure.web.RequestSizeLimitFilter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, RequestSizeLimitFilter.class})
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    @MockitoBean
    private JwtDecoder jwtDecoder; // nenhum servidor de autenticação real: tokens vêm do post-processor jwt()

    private static JwtRequestPostProcessor writer() {
        return jwt().jwt(j -> j.claim("client_id", "system-a"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_WRITE));
    }

    private static JwtRequestPostProcessor reader() {
        return jwt().jwt(j -> j.claim("client_id", "system-a"))
                .authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_READ));
    }

    private static ActivationRequest sampleRequest() {
        return ActivationRequest.create("system-a", "EXT-12345", "CUST-10001", "INTERNET-500", "c1",
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    @Nested
    class Create {

        @Test
        void returns201WithLocationWhenCreated() throws Exception {
            var request = sampleRequest();
            when(service.create(any())).thenReturn(new CreationResult(request, true));

            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/api/v1/requests/" + request.getId())))
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.id").value(request.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.clientId").doesNotExist())
                    .andExpect(jsonPath("$.version").doesNotExist());

            var captor = ArgumentCaptor.forClass(CreateActivationCommand.class);
            verify(service).create(captor.capture());
            assertThat(captor.getValue().clientId()).isEqualTo("system-a");
            assertThat(captor.getValue().externalId()).isEqualTo("EXT-12345");
            assertThat(captor.getValue().correlationId()).isNotBlank();
        }

        @Test
        void returns200WithExistingRecordOnIdempotentRetry() throws Exception {
            when(service.create(any())).thenReturn(new CreationResult(sampleRequest(), false));

            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"));
        }

        @Test
        void returns409WhenExternalIdReusedWithDifferentContent() throws Exception {
            var existingId = UUID.randomUUID();
            when(service.create(any())).thenThrow(new DuplicateRequestConflictException("EXT-12345", existingId));

            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/duplicate-request"))
                    .andExpect(jsonPath("$.existingRequestId").value(existingId.toString()));
        }

        @Test
        void returns400WithFieldErrorsForInvalidBody() throws Exception {
            mvc.perform(post("/api/v1/requests").with(writer())
                            .header("X-Correlation-Id", "abc123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"externalId":"EXT 1","productCode":"%s"}
                                    """.formatted("X".repeat(65))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Dados inválidos"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                    .andExpect(jsonPath("$.traceId").value("abc123"))
                    .andExpect(jsonPath("$.errors[*].field", hasItem("customerId")))
                    .andExpect(jsonPath("$.errors[*].field", hasItem("externalId")))
                    .andExpect(jsonPath("$.errors[*].field", hasItem("productCode")));

            verifyNoInteractions(service);
        }

        @Test
        void returns400ForMalformedJsonWithoutInternalDetails() throws Exception {
            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.traceId", notNullValue()))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist());
        }

        @Test
        void returns413WhenBodyTooLarge() throws Exception {
            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content("x".repeat(17 * 1024)))
                    .andExpect(status().isPayloadTooLarge());
        }

        @Test
        void returns500WithoutStackTraceOnUnexpectedError() throws Exception {
            when(service.create(any())).thenThrow(new IllegalStateException("db password=secret"));

            mvc.perform(post("/api/v1/requests").with(writer())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado. Informe o traceId ao suporte."))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }

    @Nested
    class Query {

        @Test
        void returnsExistingRequest() throws Exception {
            var request = sampleRequest();
            when(service.get("system-a", request.getId())).thenReturn(request);

            mvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(request.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void returns404WhenNotFound() throws Exception {
            var id = UUID.randomUUID();
            when(service.get("system-a", id)).thenThrow(new RequestNotFoundException(id));

            mvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void returns400ForInvalidUuid() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", "not-a-uuid").with(reader()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("id"));
        }

        @Test
        void returns400ForPageSizeAboveLimit() throws Exception {
            mvc.perform(get("/api/v1/requests").param("size", "500").with(reader()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("size"));
        }
    }

    @Nested
    class Security {

        @Test
        void returns401WithoutToken() throws Exception {
            mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

            verifyNoInteractions(service);
        }

        @Test
        void returns403WhenReaderTriesToCreate() throws Exception {
            mvc.perform(post("/api/v1/requests").with(reader())
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Acesso negado"));

            verifyNoInteractions(service);
        }

        @Test
        void returns403WhenWriterTriesToRead() throws Exception {
            mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(writer()))
                    .andExpect(status().isForbidden());
        }
    }
}
