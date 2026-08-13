package com.personal.baton.adapter.in.web.restdocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.config.WatchEventReceiverAuthentication;
import com.personal.baton.adapter.in.web.config.WatchEventReceiverAuthenticationFilter;
import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import com.personal.baton.adapter.in.web.watch.WatchHealthEventRequest;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.error.WatchHealthEventConflictException;
import com.personal.baton.application.watch.error.WatchHealthEventChangedAtOutOfRangeException;
import com.personal.baton.application.watch.error.WatchHealthEventIdMismatchException;
import com.personal.baton.application.watch.error.WatchHealthEventResourceReferenceException;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.AcceptWatchHealthEventCommand;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.WatchHealthEventReceipt;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class WatchHealthEventRestDocsTest {

    private static final UUID EVENT_ID =
            UUID.fromString("8cf76651-f98d-4755-b578-1629b0ca2f55");
    private static final UUID ATTEMPT_ID =
            UUID.fromString("81ccb9da-f9f9-4abc-87fe-cf6193ee5f79");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String TOKEN = "receiver-token-with-at-least-32-characters";
    private static final String DESCRIPTION =
            "WATCH가 전달한 역할 자료 상태 변경 이벤트를 eventId 기준으로 멱등 수신하는 "
                    + "서비스 간 콜백이며 일반 프런트엔드에서 호출하지 않는다.";
    private static final String SUMMARY = "WATCH 전용 역할 자료 상태 변경 이벤트 수신";

    private AcceptWatchHealthEventUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        useCase = mock(AcceptWatchHealthEventUseCase.class);
        mockMvc = standaloneSetup(new WatchHealthEventController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(
                        new RequestIdFilter(() -> REQUEST_ID),
                        new WatchEventReceiverAuthenticationFilter(
                                WatchEventReceiverAuthentication.enabled(TOKEN)
                        )
                )
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .build();
    }

    @DisplayName("WATCH health 변경 이벤트 API는 멱등 receipt를 202로 반환한다")
    @Test
    void documentsAcceptedHealthEvent() throws Exception {
        Instant acceptedAt = Instant.parse("2026-08-02T03:04:06Z");
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenReturn(new WatchHealthEventReceipt(EVENT_ID, acceptedAt));

        mockMvc.perform(authenticatedPost(validRequest(true)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.acceptedAt").value("2026-08-02T03:04:06Z"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "acceptWatchHealthEvent",
                        DESCRIPTION,
                        SUMMARY,
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION)
                                        .description("WATCH 이벤트 전달 전용 Bearer 토큰"),
                                headerWithName("Idempotency-Key")
                                        .description("본문 eventId와 같은 UUID")
                        ),
                        requestFields(
                                requestField("eventId", "불변 이벤트 UUID이자 멱등 식별자"),
                                requestField("eventType", "고정 이벤트 유형 RESOURCE_HEALTH_CHANGED"),
                                requestField(
                                        "resourceReference",
                                        "설정된 BATON 네임스페이스의 정규 역할 자료 참조"
                                ),
                                requestField("sourceRevision", "모니터 스냅샷의 0 이상 소스 리비전"),
                                fieldWithPath("attemptId")
                                        .optional()
                                        .description("완료된 점검이 변경을 만들었을 때의 점검 시도 UUID"),
                                requestEnumField("previousHealth", "변경 전 상태"),
                                requestEnumField("currentHealth", "변경 후 상태"),
                                requestField(
                                        "changedAt",
                                        "1000년 이상 10000년 미만 범위에서 상태가 변경된 UTC 시각"
                                )
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자")
                        ),
                        responseFields(
                                fieldWithPath("eventId").description("수신한 이벤트 UUID"),
                                fieldWithPath("acceptedAt").description("최초로 영속 수신한 UTC 시각")
                        )
                ));

        ArgumentCaptor<AcceptWatchHealthEventCommand> command =
                ArgumentCaptor.forClass(AcceptWatchHealthEventCommand.class);
        verify(useCase).accept(eq(EVENT_ID), command.capture());
        assertThat(command.getValue())
                .extracting(
                        AcceptWatchHealthEventCommand::eventId,
                        AcceptWatchHealthEventCommand::eventType,
                        AcceptWatchHealthEventCommand::resourceReference,
                        AcceptWatchHealthEventCommand::sourceRevision,
                        AcceptWatchHealthEventCommand::attemptId,
                        AcceptWatchHealthEventCommand::previousHealth,
                        AcceptWatchHealthEventCommand::currentHealth,
                        AcceptWatchHealthEventCommand::changedAt
                )
                .containsExactly(
                        EVENT_ID,
                        "RESOURCE_HEALTH_CHANGED",
                        "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        7L,
                        ATTEMPT_ID,
                        WatchResourceHealth.DEGRADED,
                        WatchResourceHealth.BROKEN,
                        Instant.parse("2026-08-02T03:04:05Z")
                );
    }

    @DisplayName("WATCH health 변경 이벤트는 점검과 무관한 변경에서 attemptId를 생략할 수 있다")
    @Test
    void acceptHealthEventWithoutAttemptId() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenReturn(new WatchHealthEventReceipt(
                        EVENT_ID,
                        Instant.parse("2026-08-02T03:04:06Z")
                ));

        mockMvc.perform(authenticatedPost(validRequest(false)))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AcceptWatchHealthEventCommand> command =
                ArgumentCaptor.forClass(AcceptWatchHealthEventCommand.class);
        verify(useCase).accept(eq(EVENT_ID), command.capture());
        assertThat(command.getValue().attemptId()).isNull();
    }

    @DisplayName("Idempotency-Key와 eventId가 다르면 안정적인 400 오류를 반환한다")
    @Test
    void documentsIdempotencyKeyMismatch() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenThrow(new WatchHealthEventIdMismatchException());

        mockMvc.perform(authenticatedPost(validRequest(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISMATCH"))
                .andDo(documentError("acceptWatchHealthEventIdempotencyKeyMismatch"));
    }

    @DisplayName("현재 BATON namespace가 아닌 resourceReference는 안정적인 400 오류를 반환한다")
    @Test
    void documentsInvalidResourceReference() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenThrow(new WatchHealthEventResourceReferenceException());

        mockMvc.perform(authenticatedPost(validRequest(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WATCH_RESOURCE_REFERENCE_INVALID"))
                .andDo(documentError("acceptWatchHealthEventInvalidResourceReference"));
    }

    @DisplayName("같은 eventId에 다른 payload가 저장돼 있으면 안정적인 409 오류를 반환한다")
    @Test
    void documentsEventIdConflict() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenThrow(new WatchHealthEventConflictException());

        mockMvc.perform(authenticatedPost(validRequest(true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATCH_EVENT_ID_CONFLICT"))
                .andDo(documentError("acceptWatchHealthEventIdConflict"));
    }

    @DisplayName("WATCH health 변경 이벤트의 알려지지 않은 필드는 400으로 거부한다")
    @Test
    void rejectUnknownRequestField() throws Exception {
        String request = validRequest(true).replace(
                "\"changedAt\": \"2026-08-02T03:04:05Z\"",
                "\"changedAt\": \"2026-08-02T03:04:05Z\",\n  \"targetUrl\": \"https://secret.example\""
        );

        mockMvc.perform(authenticatedPost(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("WATCH health 변경 시각이 RFC 3339 문자열이 아니면 400으로 거부한다")
    @Test
    void rejectNumericChangedAt() throws Exception {
        String request = validRequest(true).replace(
                "\"changedAt\": \"2026-08-02T03:04:05Z\"",
                "\"changedAt\": 1785649445"
        );

        mockMvc.perform(authenticatedPost(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("WATCH health 변경 시각이 달력에 존재하지 않으면 400으로 거부한다")
    @Test
    void rejectNonexistentChangedAt() throws Exception {
        String request = validRequest(true).replace(
                "2026-08-02T03:04:05Z",
                "2026-02-30T03:04:05Z"
        );

        mockMvc.perform(authenticatedPost(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("WATCH health 변경 시각은 RFC 3339 UTC offset 표기도 허용한다")
    @Test
    void acceptsZeroOffsetChangedAt() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenReturn(new WatchHealthEventReceipt(
                        EVENT_ID,
                        Instant.parse("2026-08-02T03:04:06Z")
                ));
        String request = validRequest(true).replace(
                "2026-08-02T03:04:05Z",
                "2026-08-02T03:04:05+00:00"
        );

        mockMvc.perform(authenticatedPost(request))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AcceptWatchHealthEventCommand> command =
                ArgumentCaptor.forClass(AcceptWatchHealthEventCommand.class);
        verify(useCase).accept(eq(EVENT_ID), command.capture());
        assertThat(command.getValue().changedAt())
                .isEqualTo(Instant.parse("2026-08-02T03:04:05Z"));
    }

    @DisplayName("WATCH health가 바뀌지 않은 이벤트는 400 입력 오류로 거부한다")
    @Test
    void documentsUnchangedHealthValidation() throws Exception {
        String request = validRequest(true).replace(
                "\"currentHealth\": \"BROKEN\"",
                "\"currentHealth\": \"DEGRADED\""
        );

        mockMvc.perform(authenticatedPost(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(documentError("acceptWatchHealthEventInvalidInput"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("저장 범위를 벗어난 changedAt은 재시도할 수 없는 400 입력 오류로 반환한다")
    @Test
    void documentsChangedAtOutsidePersistenceRange() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenThrow(new WatchHealthEventChangedAtOutOfRangeException());

        mockMvc.perform(authenticatedPost(validRequest(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @DisplayName("WATCH bearer token이 없으면 본문 처리 전에 일반화된 401 오류를 반환한다")
    @Test
    void documentsUnauthorizedRequest() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header("Idempotency-Key", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andDo(documentError("acceptWatchHealthEventUnauthorized"));

        verifyNoInteractions(useCase);
    }

    private MockHttpServletRequestBuilder authenticatedPost(String request) {
        return post(WatchHealthEventController.PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("Idempotency-Key", EVENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request);
    }

    private String validRequest(boolean includeAttemptId) {
        String attemptField = includeAttemptId
                ? "  \"attemptId\": \"81ccb9da-f9f9-4abc-87fe-cf6193ee5f79\",\n"
                : "";
        return """
                {
                  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
                  "eventType": "RESOURCE_HEALTH_CHANGED",
                  "resourceReference": "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "sourceRevision": 7,
                %s  "previousHealth": "DEGRADED",
                  "currentHealth": "BROKEN",
                  "changedAt": "2026-08-02T03:04:05Z"
                }
                """.formatted(attemptField);
    }

    private FieldDescriptor requestField(String path, String description) {
        return new ConstrainedFields(WatchHealthEventRequest.class)
                .withPath(path)
                .description(description);
    }

    private FieldDescriptor requestEnumField(String path, String description) {
        FieldDescriptor descriptor = new EnumFields(WatchResourceHealth.class)
                .withPath(path)
                .description(description);
        return new ConstrainedFields(WatchHealthEventRequest.class)
                .addConstraints(descriptor, path);
    }

    private ResultHandler documentError(String identifier) {
        return MockMvcRestDocumentationWrapper.document(
                identifier,
                DESCRIPTION,
                SUMMARY,
                responseHeaders(
                        headerWithName(RequestIdFilter.HEADER_NAME)
                                .description("서버가 생성한 불투명 요청 진단 식별자")
                ),
                responseFields(
                        fieldWithPath("code").description("안정적인 오류 코드"),
                        fieldWithPath("message").description("안전한 오류 설명")
                )
        );
    }
}
