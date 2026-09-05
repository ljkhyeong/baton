package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.workspace.ResourceVerificationController;
import com.personal.baton.adapter.in.web.workspace.ResourceVerificationController.VerifyResourceRequest;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerificationHistoryResult;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerificationResult;
import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import java.time.Instant;
import java.time.LocalDate;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.ReviewScheduleResult;
import com.personal.baton.adapter.in.web.workspace.ResourceReviewScheduleRequest;
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

import static org.springframework.restdocs.snippet.Attributes.key;
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
class ResourceVerificationRestDocsTest {
    private static final UUID TEAM = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SEASON = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private MockMvc mvc;
    private ResourceVerificationUseCase useCase;

    @BeforeEach
    void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(ResourceVerificationUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ResourceVerificationController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation)).build();
    }

    @Test
    @DisplayName("재확인 목록은 시즌 날짜와 기한이 된 자료의 현재 담당자를 반환한다")
    void documentsDueReviews() throws Exception {
        when(useCase.getDueReviews(TEAM, SEASON, "key")).thenReturn(new ResourceVerificationUseCase.DueReviewsResult(
                TEAM, SEASON, LocalDate.of(2026, 9, 5), "Asia/Seoul", List.of(new ResourceVerificationUseCase.DueReviewResult(
                RESOURCE, ACCOUNT, "운영 안내", "기록 담당", null, null, LocalDate.of(2026, 9, 5)))));
        mvc.perform(get(ResourceVerificationController.DUE_PATH, TEAM, SEASON).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.today").value("2026-09-05"))
                .andExpect(jsonPath("$.resources[0].resourceId").value(RESOURCE.toString()))
                .andExpect(jsonPath("$.resources[0].nextReviewOn").value("2026-09-05"))
                .andDo(MockMvcRestDocumentationWrapper.document("getDueResourceReviews",
                        "시즌 날짜 기준 재확인 기한이 된 활성 자료를 확인일·식별자 순으로 반환한다. 종료 시즌은 빈 목록이다.", "재확인할 자료 목록",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("seasonId").description("시즌 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 팀의 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("응답 캐시 금지")),
                        responseFields(fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("seasonId").description("시즌 식별자"),
                                fieldWithPath("today").description("시즌 현지 오늘 날짜"), fieldWithPath("timeZone").description("시즌 IANA 시간대"),
                                fieldWithPath("resources").type(JsonFieldType.ARRAY).attributes(key("itemsType").value(JsonFieldType.OBJECT)).description("재확인할 활성 자료"),
                                fieldWithPath("resources[].resourceId").description("자료 식별자"), fieldWithPath("resources[].roleId").description("소속 역할 식별자"),
                                fieldWithPath("resources[].title").description("자료 이름"), fieldWithPath("resources[].roleName").description("역할 이름"),
                                fieldWithPath("resources[].memberId").type(JsonFieldType.STRING).optional().description("현재 활성 담당자 식별자. 없으면 null"),
                                fieldWithPath("resources[].memberName").type(JsonFieldType.STRING).optional().description("현재 활성 담당자 이름. 없으면 null"),
                                fieldWithPath("resources[].nextReviewOn").description("다음 확인일"))));
    }

    @Test
    @DisplayName("자료 확인 이력은 자료 버전과 최근 확인자의 이름을 반환한다")
    void documentsHistory() throws Exception {
        when(useCase.getHistory(TEAM, SEASON, RESOURCE, "key")).thenReturn(history());
        mvc.perform(get(ResourceVerificationController.PATH, TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.verifications[0].current").value(true))
                .andDo(MockMvcRestDocumentationWrapper.document("getResourceVerifications",
                        "자료 버전과 최근 20건의 수동 확인 이력을 반환한다.", "자료 확인 이력 조회",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"),
                                parameterWithName("seasonId").description("시즌 식별자"),
                                parameterWithName("resourceId").description("자료 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("개인 데이터 캐시 금지")), fields()));
    }

    @Test
    @DisplayName("자료 확인은 로그인 계정과 확인한 버전 및 메모를 전달한다")
    void documentsVerification() throws Exception {
        when(useCase.verify(any(), any(), any(), any(), any(), any())).thenReturn(history());
        AuthenticatedAccountPrincipal principal = new TestPrincipal(ACCOUNT, 0);
        mvc.perform(post(ResourceVerificationController.PATH, TEAM, SEASON, RESOURCE)
                        .header("X-Baton-Access-Key", "key")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedAccountId":"%s","resourceVersion":2,"status":"CONFIRMED","note":"문서 접근과 내용 확인"}
                                """.formatted(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("verifyResource",
                        "로그인한 활성 구성원의 수동 확인을 남긴다. 자료 버전이 달라졌거나 보관되었으면 409를 반환한다.", "자료 확인 기록",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"),
                                parameterWithName("seasonId").description("시즌 식별자"),
                                parameterWithName("resourceId").description("자료 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 접근 키").optional()),
                        requestFields(new ConstrainedFields(VerifyResourceRequest.class).withPath("expectedAccountId").description("화면에서 확인한 로그인 계정"),
                                new ConstrainedFields(VerifyResourceRequest.class).withPath("resourceVersion").description("확인한 자료 버전"),
                                new EnumFields(ResourceVerificationStatus.class).withPath("status").description("확인 결과"),
                                new ConstrainedFields(VerifyResourceRequest.class).withPath("note").description("확인 메모").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("개인 데이터 캐시 금지")), fields()));
        verify(useCase).verify(eq(TEAM), eq(SEASON), eq(RESOURCE), eq("key"), eq(ACCOUNT),
                argThat(command -> command.resourceVersion() == 2 && command.status() == ResourceVerificationStatus.CONFIRMED));
    }

    @Test @DisplayName("자료 재확인 일정은 시즌의 오늘 날짜와 다음 확인일 및 수정 버전을 반환한다")
    void documentsSchedule() throws Exception {
        when(useCase.getSchedule(TEAM, SEASON, RESOURCE, "key")).thenReturn(schedule());
        mvc.perform(get(ResourceVerificationController.SCHEDULE_PATH, TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.today").value("2026-09-05")).andExpect(jsonPath("$.reviewDue").value(true))
                .andDo(MockMvcRestDocumentationWrapper.document("getResourceReviewSchedule", "자료별 재확인 주기와 시즌 시간대의 확인 기한을 조회한다.", "자료 재확인 일정 조회",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("seasonId").description("시즌 식별자"), parameterWithName("resourceId").description("자료 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 팀의 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("일정 캐시 금지")), scheduleFields()));
    }
    @Test @DisplayName("연결된 구성원은 확인 일정의 버전을 확인하고 재확인 주기를 설정한다")
    void documentsConfigureSchedule() throws Exception {
        when(useCase.configureSchedule(any(), any(), any(), any(), any(), any())).thenReturn(schedule());
        var fields = new ConstrainedFields(ResourceReviewScheduleRequest.class);
        mvc.perform(post(ResourceVerificationController.SCHEDULE_PATH, TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", "key")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(new TestPrincipal(ACCOUNT, 0), null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedAccountId":"%s","expectedVersion":-1,"intervalDays":30,"nextReviewOn":"2026-09-05"}
                                """.formatted(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("configureResourceReviewSchedule", "연결된 활성 구성원이 재확인 주기와 첫 확인일을 지정하거나 함께 해제한다.", "자료 재확인 일정 설정",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("seasonId").description("시즌 식별자"), parameterWithName("resourceId").description("자료 식별자")),
                        requestFields(fields.withPath("expectedAccountId").description("현재 로그인 계정"), fields.withPath("expectedVersion").description("조회한 일정 버전. 미설정은 -1"),
                                fields.withPath("intervalDays").type(JsonFieldType.NUMBER).optional().description("확인 간격 1~365일. 해제는 null"),
                                fields.withPath("nextReviewOn").type(JsonFieldType.STRING).optional().description("시즌 달력 기준 다음 확인일. 해제는 null")),
                        responseHeaders(headerWithName("Cache-Control").description("일정 캐시 금지")), scheduleFields()));
    }
    private ReviewScheduleResult schedule() { return new ReviewScheduleResult(TEAM, SEASON, RESOURCE, 0, 30,
            LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), true); }
    private ResponseFieldsSnippet scheduleFields() {
        return responseFields(fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("seasonId").description("시즌 식별자"),
                fieldWithPath("resourceId").description("자료 식별자"), fieldWithPath("version").description("일정 버전. 미설정은 -1"),
                fieldWithPath("intervalDays").type(JsonFieldType.NUMBER).optional().description("재확인 간격. 해제는 null"),
                fieldWithPath("nextReviewOn").type(JsonFieldType.STRING).optional().description("다음 확인일. 해제는 null"),
                fieldWithPath("today").description("시즌 시간대의 오늘 날짜"), fieldWithPath("reviewDue").description("다음 확인일 당일 또는 지났는지 여부"));
    }

    private record TestPrincipal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}

    private VerificationHistoryResult history() {
        return new VerificationHistoryResult(TEAM, SEASON, RESOURCE, 2, List.of(new VerificationResult(
                UUID.fromString("00000000-0000-4000-8000-000000000005"), 2, ACCOUNT, "박민서",
                "https://example.com/guide", ResourceVerificationStatus.CONFIRMED, "문서 접근과 내용 확인",
                Instant.parse("2026-09-05T03:00:00Z"), true)));
    }

    private ResponseFieldsSnippet fields() {
        return responseFields(fieldWithPath("teamId").type(JsonFieldType.STRING).description("팀 식별자"),
                fieldWithPath("seasonId").type(JsonFieldType.STRING).description("시즌 식별자"),
                fieldWithPath("resourceId").type(JsonFieldType.STRING).description("자료 식별자"),
                fieldWithPath("resourceVersion").type(JsonFieldType.NUMBER).description("현재 자료 버전"),
                fieldWithPath("verifications").type(JsonFieldType.ARRAY).description("최근 확인 20건"),
                fieldWithPath("verifications[].id").description("확인 기록 식별자"),
                fieldWithPath("verifications[].resourceVersion").description("확인 당시 자료 버전"),
                fieldWithPath("verifications[].memberId").description("확인한 구성원 식별자"),
                fieldWithPath("verifications[].memberName").description("확인 당시 구성원 이름"),
                fieldWithPath("verifications[].url").description("확인 당시 주소"),
                new EnumFields(ResourceVerificationStatus.class).withPath("verifications[].status").description("확인 결과"),
                fieldWithPath("verifications[].note").description("확인 메모").optional(),
                fieldWithPath("verifications[].verifiedAt").description("서버 확인 시각"),
                fieldWithPath("verifications[].current").description("현재 자료 버전에 대한 확인 여부"));
    }
}
