package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.link.RoleResourceLinkController;
import com.personal.baton.adapter.in.web.link.RoleResourceLinkRequest;
import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.error.LinkGatewayConflictException;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.OpenRoleResourceLinkResult;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.RoutingMode;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class RoleResourceLinkRestDocsTest {

    private static final String SUMMARY = "역할 자료 열기 링크 해석";
    private static final String DESCRIPTION =
            "워크스페이스 접근을 확인하고 일반 자료 URL 또는 BATON GO의 만료 short URL을 반환한다.";
    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String ACCESS_KEY = "baton-access-key";
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-30T12:15:00Z");

    private RoleResourceLinkUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        useCase = mock(RoleResourceLinkUseCase.class);
        mockMvc = standaloneSetup(new RoleResourceLinkController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .build();
    }

    @DisplayName("역할 자료 열기 API는 검증된 ROUND 자료의 만료 short URL을 반환한다")
    @Test
    void documentsOpenRoleResourceLink() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenReturn(new OpenRoleResourceLinkResult(
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                RoutingMode.BATON_GO,
                EXPIRES_AT
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expiresAt": "2026-07-30T12:15:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.navigationUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(jsonPath("$.routingMode").value("BATON_GO"))
                .andExpect(jsonPath("$.expiresAt").value("2026-07-30T12:15:00Z"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "openRoleResourceLink",
                        DESCRIPTION,
                        SUMMARY,
                        pathParameters(
                                parameterWithName("teamId").description("팀 UUID"),
                                parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("resourceId").description("역할 자료 UUID")
                        ),
                        requestHeaders(
                                headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키"),
                                headerWithName("Idempotency-Key")
                                        .description("같은 열기 intent를 재시도할 canonical UUID")
                        ),
                        requestFields(
                                new ConstrainedFields(RoleResourceLinkRequest.class)
                                        .withPath("expiresAt")
                                        .description("현재부터 최대 15분 이내인 UTC 만료 시각")
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName("Cache-Control")
                                        .description("공개 short URL 응답을 저장하지 않는 no-store 지시자")
                        ),
                        responseFields(
                                fieldWithPath("navigationUrl")
                                        .description("브라우저가 이동할 절대 http 또는 https URL"),
                                new EnumFields(RoutingMode.class)
                                        .withPath("routingMode")
                                        .description("DIRECT 또는 BATON_GO 라우팅 방식"),
                                fieldWithPath("expiresAt")
                                        .optional()
                                        .description("BATON GO 링크 만료 시각이며 DIRECT이면 null")
                        )
                ));
    }

    @DisplayName("역할 자료 열기 API는 canonical UUID가 아닌 멱등 키를 안정적인 오류로 거절한다")
    @Test
    void documentsOpenRoleResourceLinkInvalidIdempotencyKey() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new InvalidLinkIntentException(
                "INVALID_LINK_IDEMPOTENCY_KEY",
                "Idempotency-Key는 canonical UUID 형식이어야 합니다"
        ));

        performOpenRequest()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINK_IDEMPOTENCY_KEY"))
                .andDo(documentError("openRoleResourceLinkInvalidIdempotencyKey"));
    }

    @DisplayName("역할 자료 열기 API는 비정규 ROUND URL을 직접 이동으로 우회하지 않는다")
    @Test
    void documentsOpenRoleResourceLinkInvalidRoundUrl() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new InvalidLinkIntentException(
                "INVALID_ROUND_RESOURCE_URL",
                "ROUND 자료 링크는 query와 fragment가 없는 canonical room URL이어야 합니다"
        ));

        performOpenRequest()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROUND_RESOURCE_URL"))
                .andDo(documentError("openRoleResourceLinkInvalidRoundUrl"));
    }

    @DisplayName("역할 자료 열기 API는 워크스페이스 접근 키가 틀리면 링크를 반환하지 않는다")
    @Test
    void documentsOpenRoleResourceLinkAccessDenied() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new WorkspaceAccessDeniedException());

        performOpenRequest()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(documentError("openRoleResourceLinkAccessDenied"));
    }

    @DisplayName("역할 자료 열기 API는 다른 워크스페이스 자료를 찾을 수 없음으로 반환한다")
    @Test
    void documentsOpenRoleResourceLinkNotFound() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new WorkspaceNotFoundException(
                "ROLE_RESOURCE_NOT_FOUND",
                "자료를 찾을 수 없습니다"
        ));

        performOpenRequest()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_RESOURCE_NOT_FOUND"))
                .andDo(documentError("openRoleResourceLinkNotFound"));
    }

    @DisplayName("역할 자료 열기 API는 GO 멱등 충돌을 안정적인 충돌 오류로 반환한다")
    @Test
    void documentsOpenRoleResourceLinkConflict() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new LinkGatewayConflictException());

        performOpenRequest()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINK_GATEWAY_CONFLICT"))
                .andDo(documentError("openRoleResourceLinkConflict"));
    }

    @DisplayName("역할 자료 열기 API는 GO 장애를 원본 링크 우회 없이 gateway 오류로 반환한다")
    @Test
    void documentsOpenRoleResourceLinkUnavailable() throws Exception {
        when(useCase.openRoleResourceLink(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(RESOURCE_ID),
                eq(ACCESS_KEY),
                eq(IDEMPOTENCY_KEY.toString()),
                eq(EXPIRES_AT)
        )).thenThrow(new LinkGatewayUnavailableException());

        performOpenRequest()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINK_GATEWAY_UNAVAILABLE"))
                .andDo(documentError("openRoleResourceLinkUnavailable"));
    }

    private ResultActions performOpenRequest() throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID)
                .header("X-Baton-Access-Key", ACCESS_KEY)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "expiresAt": "2026-07-30T12:15:00Z"
                        }
                        """));
    }

    private RestDocumentationResultHandler documentError(String resourceIdentifier) {
        return MockMvcRestDocumentationWrapper.document(
                resourceIdentifier,
                DESCRIPTION,
                SUMMARY,
                pathParameters(
                        parameterWithName("teamId").description("팀 UUID"),
                        parameterWithName("seasonId").description("시즌 UUID"),
                        parameterWithName("resourceId").description("역할 자료 UUID")
                ),
                requestHeaders(
                        headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키"),
                        headerWithName("Idempotency-Key")
                                .description("같은 열기 intent를 재시도할 canonical UUID")
                ),
                requestFields(
                        new ConstrainedFields(RoleResourceLinkRequest.class)
                                .withPath("expiresAt")
                                .description("현재부터 최대 15분 이내인 UTC 만료 시각")
                ),
                responseHeaders(
                        headerWithName(RequestIdFilter.HEADER_NAME)
                                .description("서버가 생성한 불투명 요청 진단 식별자")
                ),
                responseFields(
                        fieldWithPath("code").description("안정적인 오류 코드"),
                        fieldWithPath("message").description("사용자에게 표시할 오류 설명")
                )
        );
    }
}
