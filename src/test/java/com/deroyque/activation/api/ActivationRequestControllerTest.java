package com.deroyque.activation.api;

import com.deroyque.activation.api.impl.ActivationRequestControllerImpl;
import com.deroyque.activation.config.CorrelationIdFilter;
import com.deroyque.activation.config.SecurityConfig;
import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.support.TestTokens;
import com.deroyque.activation.vo.CreationResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(ActivationRequestControllerImpl.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
class ActivationRequestControllerTest {

    private static final UUID REQUEST_ID = UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90");
    private static final String GET_BY_ID_PATH = RouteConstants.ActivationRequest.BASE + RouteConstants.ActivationRequest.GET_BY_ID;
    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;
    private static final CreateActivationRequestDTO CREATE_ACTIVATION_REQUEST_DTO = new CreateActivationRequestDTO("EXT-12345", "CUST-10001",
            "INTERNET-500");

    @Autowired
    private MockMvcTester mockMvcTester;

    @MockitoBean
    private ActivationRequestService activationRequestService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturnCreatedWithLocationWhenRequestIsCreated() {

        given(activationRequestService.create(CREATE_ACTIVATION_REQUEST_DTO))
                .willReturn(new CreationResultVO(pendingActivationRequest(), Boolean.TRUE));

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CREATED);
        assertThat(mvcTestResult).headers().containsHeader("X-Correlation-Id");
        assertThat(mvcTestResult.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(RouteConstants.ActivationRequest.BASE + "/" + REQUEST_ID);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.id").isEqualTo(REQUEST_ID.toString());
        assertThat(mvcTestResult).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-10-26T14:03:11Z");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.failureReason");
    }

    @Test
    void shouldReturnOkWithoutLocationWhenRetryHasSameContent() {

        given(activationRequestService.create(CREATE_ACTIVATION_REQUEST_DTO))
                .willReturn(new CreationResultVO(pendingActivationRequest(), Boolean.FALSE));

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertThat(mvcTestResult).hasStatusOk();
        assertThat(mvcTestResult).headers().doesNotContainHeader(HttpHeaders.LOCATION);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.externalId").isEqualTo("EXT-12345");
    }

    @Test
    void shouldReturnConflictWhenExternalIdIsReusedWithDifferentContent() {

        UUID existingId = UUID.randomUUID();
        given(activationRequestService.create(CREATE_ACTIVATION_REQUEST_DTO))
                .willThrow(new BusinessServiceException(ErrorEnum.DUPLICATE_REQUEST, Map.of("existingRequestId", existingId), "EXT-12345"));

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.type").isEqualTo("https://api.exemplo.com/problems/duplicate-request");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.detail").asString().contains("EXT-12345");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.existingRequestId").isEqualTo(existingId.toString());
    }

    @Test
    void shouldReturnBadRequestWithFieldsWhenBodyIsInvalid() {

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .header("X-Correlation-Id", "abc123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT 1","productCode":"%s"}
                        """.formatted("X".repeat(65)))
                .exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.title").isEqualTo("Invalid data");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.instance").isEqualTo(RouteConstants.ActivationRequest.BASE);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.traceId").isEqualTo("abc123");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactlyInAnyOrder("customerId", "productCode");
        assertThat(mvcTestResult).bodyJson().extractingPath("$.errors[?(@.field == 'customerId')].message").asArray().containsExactly("must not be blank");
        then(activationRequestService).shouldHaveNoInteractions();
    }

    @Test
    void shouldReturnBadRequestWithoutInternalDetailsWhenJsonIsMalformed() {

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .contentType(MediaType.APPLICATION_JSON).content("{not-json").exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvcTestResult).bodyJson().hasPath("$.traceId");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.trace");
        assertThat(mvcTestResult).bodyJson().doesNotHavePath("$.exception");
    }

    @Test
    void shouldReturnInternalErrorWithoutDetailsWhenUnexpectedFailureHappens() {

        given(activationRequestService.create(CREATE_ACTIVATION_REQUEST_DTO))
                .willThrow(new IllegalStateException("db password=secret"));

        MvcTestResult mvcTestResult = mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.writer())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertThat(mvcTestResult).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(mvcTestResult).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred. Please send the traceId to support.");
        assertThat(mvcTestResult).bodyText().doesNotContain("secret");
    }

    @Test
    void shouldReturnRequestWhenItExists() {

        given(activationRequestService.get(REQUEST_ID)).willReturn(pendingActivationRequest());

        assertThat(mockMvcTester.get().uri(GET_BY_ID_PATH, REQUEST_ID).with(TestTokens.reader()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.id").isEqualTo(REQUEST_ID.toString());
    }

    @Test
    void shouldReturnNotFoundWhenRequestDoesNotExist() {

        given(activationRequestService.get(REQUEST_ID)).willThrow(new BusinessServiceException(ErrorEnum.REQUEST_NOT_FOUND));

        assertThat(mockMvcTester.get().uri(GET_BY_ID_PATH, REQUEST_ID).with(TestTokens.reader()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.status").isEqualTo(404);
    }

    @Test
    void shouldReturnBadRequestWhenIdIsNotUuid() {

        assertThat(mockMvcTester.get().uri(GET_BY_ID_PATH, "not-a-uuid").with(TestTokens.reader()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("id");
    }

    @Test
    void shouldReturnBadRequestWithFieldWhenPageSizeExceedsLimit() {

        assertThat(mockMvcTester.get().uri(RouteConstants.ActivationRequest.BASE).param("size", "500").with(TestTokens.reader()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
    }

    @Test
    void shouldReturnUnauthorizedWhenThereIsNoToken() {

        assertThat(mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .headers().containsHeader(HttpHeaders.WWW_AUTHENTICATE);

        then(activationRequestService).shouldHaveNoInteractions();
    }

    @Test
    void shouldReturnForbiddenWhenReadTokenTriesToCreate() {

        assertThat(mockMvcTester.post().uri(RouteConstants.ActivationRequest.BASE).with(TestTokens.reader())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.title").isEqualTo("Forbidden");

        then(activationRequestService).shouldHaveNoInteractions();
    }

    @Test
    void shouldReturnForbiddenWhenWriteTokenTriesToRead() {

        assertThat(mockMvcTester.get().uri(GET_BY_ID_PATH, REQUEST_ID).with(TestTokens.writer()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private static ActivationRequestDTO pendingActivationRequest() {

        Instant createdAt = Instant.parse("2026-10-26T14:03:11Z");

        return ActivationRequestDTO.builder()
                .id(REQUEST_ID)
                .externalId("EXT-12345")
                .customerId("CUST-10001")
                .productCode("INTERNET-500")
                .status(ActivationStatusEnum.PENDING)
                .attempts(0)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

}
