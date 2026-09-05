package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.EnumFields;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.calendar.CalendarSubscriptionController;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Scope;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Credential;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Subscription;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Status;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class CalendarSubscriptionRestDocsTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555555"
    );
    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002641"
    );
    private static final UUID TEAM_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002642"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002643"
    );
    private static final UUID EXECUTION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002644"
    );
    private static final UUID EDITION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002645"
    );
    private static final String ACCESS_KEY = "workspace-access-key";
    private static final String ETAG = "\"brief-edition-v1-test\"";

    private CalendarSubscriptionUseCase subscriptions;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        subscriptions = mock(CalendarSubscriptionUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CalendarSubscriptionController(subscriptions)
                )
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(
                                new HttpSessionSecurityContextRepository()
                        )
                ))))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();
    }

    @Test
    @DisplayName("내 구독 목록은 계정별 관리 정보와 다음 페이지를 반환하고 주소를 포함하지 않는다")
    void documentsList() throws Exception {
        var row = new CalendarSubscriptionUseCase.Summary(EDITION_ID, TEAM_ID, SEASON_ID,
                "인수인계 독서 팀", "가을 시즌", CalendarSubscriptionUseCase.ManagementStatus.CHECK_REQUIRED);
        when(subscriptions.list(ACCOUNT_ID, EXECUTION_ID, "독서", false)).thenReturn(
                new CalendarSubscriptionUseCase.SubscriptionPage(List.of(row), SEASON_ID));
        mockMvc.perform(get(CalendarSubscriptionController.LIST_PATH)
                        .queryParam("afterSeasonId", EXECUTION_ID.toString()).queryParam("query", "독서").queryParam("includeRevoked", "false")
                        .header("X-Baton-Account-Id", ACCOUNT_ID).with(authentication(accountAuthentication())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.subscriptions[0].feedUrl").doesNotExist())
                .andDo(MockMvcRestDocumentationWrapper.document("listCalendarSubscriptions",
                        "세션 계정의 구독 기록을 시즌 UUID 오름차순으로 최대 20개 조회한다. 현재 팀 권한과 공유 키는 필요 없다. CAL을 호출하지 않으며 최신 상태는 개별 조회한다.",
                        "내 캘린더 구독 목록",
                        queryParameters(
                                parameterWithName("afterSeasonId").optional().description("같은 검색·포함 조건에서 받은 nextAfterSeasonId UUID. 조건 변경 시 생략"),
                                parameterWithName("query").optional().description("팀·시즌 이름에 포함된 문자열. 최대 100자, 앞뒤 공백 제거. 생략·빈 값은 전체. %, _도 일반 문자"),
                                parameterWithName("includeRevoked").optional().description("기본 true. false면 해제 완료 기록을 제외하며 처리 중인 기록은 유지")),
                        requestHeaders(headerWithName("X-Baton-Account-Id").description("화면의 로그인 계정 UUID. 세션 계정과 일치해야 함")),
                        responseHeaders(headerWithName("Cache-Control").description("no-store")),
                        responseFields(
                                fieldWithPath("accountId").description("구독 소유 계정 UUID"),
                                fieldWithPath("subscriptions").description("해제 기록을 포함한 본인 구독. 없으면 빈 배열")
                                        .attributes(key("itemsType").value("OBJECT")),
                                fieldWithPath("subscriptions[].subscriptionId").description("CAL 구독 UUID"),
                                fieldWithPath("subscriptions[].teamId").description("팀 UUID"),
                                fieldWithPath("subscriptions[].seasonId").description("시즌 UUID"),
                                fieldWithPath("subscriptions[].teamName").description("구독 식별을 위한 현재 팀 이름"),
                                fieldWithPath("subscriptions[].seasonName").description("구독 식별을 위한 현재 시즌 이름"),
                                new EnumFields(CalendarSubscriptionUseCase.ManagementStatus.class).withPath("subscriptions[].managementStatus")
                                        .description("BATON 관리 상태. CHECK_REQUIRED는 CAL 최신 상태를 아직 조회하지 않았음을 뜻함"),
                                fieldWithPath("nextAfterSeasonId").optional().description("다음 조회 기준 시즌 UUID. 마지막 페이지는 null")
                        )));
    }

    @Test
    @DisplayName("캘린더 구독 상태 조회는 주소를 포함하지 않는다")
    void documentsStatus() throws Exception {
        when(subscriptions.find(scope())).thenReturn(new Subscription(EDITION_ID, SEASON_ID, Status.ACTIVE));
        mockMvc.perform(request(org.springframework.http.HttpMethod.GET, CalendarSubscriptionController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedUrl").doesNotExist())
                .andDo(MockMvcRestDocumentationWrapper.document("getCalendarSubscription",
                        "로그인 계정 소유의 시즌 구독 상태만 조회한다. 구독 주소는 재노출하지 않는다.", "캘린더 구독 상태 조회",
                        scopedPathParameters(), readHeaders(), responseHeaders(headerWithName("Cache-Control").description("no-store")),
                        responseFields(
                                fieldWithPath("subscriptionId").optional().description("구독 UUID. 발급 전에는 null"),
                                fieldWithPath("seasonId").description("시즌 UUID"),
                                fieldWithPath("status").description("NOT_CREATED, IN_PROGRESS, ACTIVE, REISSUE_REQUIRED, REVOKED, REVOCATION_PENDING 중 하나")
                        )));
    }

    @Test
    @DisplayName("캘린더 구독 발급과 재발급은 주소를 한 번 반환한다")
    void documentsCredentials() throws Exception {
        var credential = new Credential(EDITION_ID, SEASON_ID, URI.create("https://cal.b4ton.com/calendars/v1/" + "a".repeat(43) + ".ics"));
        when(subscriptions.create(scope())).thenReturn(credential);
        when(subscriptions.rotate(scope())).thenReturn(credential);
        documentCredential(CalendarSubscriptionController.PATH, "createCalendarSubscription", 201);
        documentCredential(CalendarSubscriptionController.ROTATE_PATH, "rotateCalendarSubscription", 200);
    }

    @Test
    @DisplayName("캘린더 구독 폐기는 주소를 반환하지 않는다")
    void documentsRevocation() throws Exception {
        mockMvc.perform(request(org.springframework.http.HttpMethod.DELETE, CalendarSubscriptionController.PATH))
                .andExpect(status().isNoContent())
                .andDo(MockMvcRestDocumentationWrapper.document("revokeCalendarSubscription",
                        "로그인 계정의 구독을 폐기한다. 이미 폐기한 요청도 204를 반환한다.", "캘린더 구독 폐기",
                        scopedPathParameters(), generationHeaders(), responseHeaders(headerWithName("Cache-Control").description("no-store"))));
    }

    private void documentCredential(String path, String operation, int statusCode) throws Exception {
        mockMvc.perform(request(org.springframework.http.HttpMethod.POST, path))
                .andExpect(status().is(statusCode))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document(operation,
                        "활성 구성원과 열린 시즌의 조회 권한을 확인하고 구독 주소를 한 번 반환한다. VIEWER도 발급할 수 있다. 재발급은 이전 주소를 무효화한다. 자동 재시도하지 않는다.",
                        "캘린더 구독 주소 발급", scopedPathParameters(), generationHeaders(),
                        responseHeaders(headerWithName("Cache-Control").description("no-store")),
                        responseFields(
                                fieldWithPath("subscriptionId").description("구독 UUID"),
                                fieldWithPath("seasonId").description("시즌 UUID"),
                                fieldWithPath("feedUrl").description("한 번만 노출하는 HTTPS 구독 주소. 영구 저장과 로그 기록 금지")
                        )));
    }

    private Scope scope() { return new Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY); }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(org.springframework.http.HttpMethod method, String path) {
        return org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.request(method, path, TEAM_ID, SEASON_ID)
                .header("X-Baton-Access-Key", ACCESS_KEY)
                .header("X-Baton-Account-Id", ACCOUNT_ID)
                .header(HttpHeaders.ORIGIN, "https://baton.example")
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-CSRF-TOKEN", "opaque-csrf-token")
                .with(authentication(accountAuthentication()));
    }

    private org.springframework.restdocs.snippet.Snippet scopedPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("BATON 팀 UUID"),
                parameterWithName("seasonId").description("BATON 시즌 UUID")
        );
    }

    private org.springframework.restdocs.snippet.Snippet readHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Account-Id").description("화면에서 확인한 로그인 계정 UUID. 세션 계정과 일치해야 함"),
                headerWithName("X-Baton-Access-Key").optional()
                        .description("공유 키 팀의 발급·재발급에만 필요한 접근 키. 계정 권한 팀과 본인 상태 조회·폐기는 생략 가능")
        );
    }

    private org.springframework.restdocs.snippet.Snippet generationHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Account-Id").description("화면에서 확인한 로그인 계정 UUID. 세션 계정과 일치해야 함"),
                headerWithName("X-Baton-Access-Key").optional()
                        .description("공유 키 팀의 발급·재발급에만 필요한 접근 키. 계정 권한 팀과 본인 상태 조회·폐기는 생략 가능"),
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 출처와 정확히 같은 브라우저 출처"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName("X-CSRF-TOKEN")
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF 토큰")
        );
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TestAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
        @Override
        public long sessionVersion() {
            return 0;
        }
    }
}
