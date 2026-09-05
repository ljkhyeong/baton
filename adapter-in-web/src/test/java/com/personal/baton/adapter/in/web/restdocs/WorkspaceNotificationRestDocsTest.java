package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.workspace.WorkspaceNotificationController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceNotificationController.ReadNotificationRequest;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase.NotificationInboxResult;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase.NotificationResult;
import com.personal.baton.domain.workspace.WorkspaceNotificationKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class WorkspaceNotificationRestDocsTest {
    private static final UUID TEAM = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SEASON = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private MockMvc mvc;
    private WorkspaceNotificationUseCase useCase;

    @BeforeEach
    void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(WorkspaceNotificationUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceNotificationController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation)).build();
    }

    @Test
    @DisplayName("내 알림함은 계정과 시즌 범위 및 업무 원본을 반환한다")
    void documentsInbox() throws Exception {
        when(useCase.getInbox(TEAM, SEASON, "key", ACCOUNT)).thenReturn(inbox(false));
        mvc.perform(get(WorkspaceNotificationController.PATH, TEAM, SEASON).header("X-Baton-Access-Key", "key")
                        .with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.notifications[0].read").value(false))
                .andDo(MockMvcRestDocumentationWrapper.document("getWorkspaceNotifications",
                        "현재 내 역할의 마감 임박·지연 업무와 수락할 인수인계를 반환한다.", "내 알림함 조회",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"),
                                parameterWithName("seasonId").description("시즌 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("개인 데이터 캐시 금지")), fields()));
    }

    @Test
    @DisplayName("알림 읽음 처리는 현재 계정의 상태만 저장한다")
    void documentsRead() throws Exception {
        when(useCase.markRead(TEAM, SEASON, "key", ACCOUNT, RESOURCE)).thenReturn(inbox(true));
        mvc.perform(post(WorkspaceNotificationController.READ_PATH, TEAM, SEASON, RESOURCE)
                        .header("X-Baton-Access-Key", "key").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAccountId\":\"" + ACCOUNT + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.notifications[0].read").value(true))
                .andDo(MockMvcRestDocumentationWrapper.document("readWorkspaceNotification",
                        "현재 내 알림을 읽음으로 표시한다. 같은 요청은 최초 읽음 시각을 유지한다.", "알림 읽음 처리",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"),
                                parameterWithName("seasonId").description("시즌 식별자"),
                                parameterWithName("notificationId").description("알림 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 접근 키").optional()),
                        requestFields(new ConstrainedFields(ReadNotificationRequest.class).withPath("expectedAccountId")
                                .description("화면에서 확인한 로그인 계정")),
                        responseHeaders(headerWithName("Cache-Control").description("개인 데이터 캐시 금지")), fields()));
    }

    private UsernamePasswordAuthenticationToken auth() {
        return UsernamePasswordAuthenticationToken.authenticated(new Principal(ACCOUNT, 0), null, List.of());
    }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
    private NotificationInboxResult inbox(boolean read) {
        return new NotificationInboxResult(ACCOUNT, TEAM, SEASON, List.of(new NotificationResult(RESOURCE,
                WorkspaceNotificationKind.OVERDUE, RESOURCE, RESOURCE, SEASON, "운영 자료 준비",
                Instant.parse("2026-09-05T03:00:00Z"), read)));
    }
    private ResponseFieldsSnippet fields() {
        return responseFields(fieldWithPath("accountId").description("현재 계정 식별자"),
                fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("seasonId").description("시즌 식별자"),
                fieldWithPath("notifications").type(JsonFieldType.ARRAY).description("현재 내 알림"),
                fieldWithPath("notifications[].id").description("알림 식별자"),
                new EnumFields(WorkspaceNotificationKind.class).withPath("notifications[].kind").description("알림 종류"),
                fieldWithPath("notifications[].sourceId").description("반복 업무 실행 또는 역할 인수인계 식별자"),
                fieldWithPath("notifications[].roleId").description("관련 역할 식별자"),
                fieldWithPath("notifications[].roundId").description("관련 회차 식별자, 인수인계는 null").optional(),
                fieldWithPath("notifications[].title").description("업무 또는 역할 이름"),
                fieldWithPath("notifications[].occurredAt").description("마감 또는 인수인계 전달 시각"),
                fieldWithPath("notifications[].read").description("현재 계정의 읽음 여부"));
    }
}
