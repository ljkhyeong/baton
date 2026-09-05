package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefAttentionController;
import com.personal.baton.adapter.in.web.brief.BriefEditionExceptionHandler;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionPage.*;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import com.personal.baton.application.brief.error.BriefAttentionQueryRejectedException;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import com.personal.baton.application.brief.BriefWeeklyResolutions;
import java.util.Arrays;
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
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class BriefAttentionRestDocsTest {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000002701");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-000000002702");
    private static final UUID SEASON = UUID.fromString("00000000-0000-0000-0000-000000002703");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000002704");
    private static final BriefAttentionUseCase.Scope SCOPE =
            new BriefAttentionUseCase.Scope(ACCOUNT, TEAM, SEASON, "access-key");
    private final BriefAttentionUseCase useCase = mock(BriefAttentionUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider documentation) {
        mvc = MockMvcBuilders.standaloneSetup(new BriefAttentionController(useCase))
                .setControllerAdvice(new BriefEditionExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new RequestIdFilter(() -> REQUEST))
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation))
                .alwaysExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST.toString()))
                .build();
    }

    @Test
    @DisplayName("이번 주 해소 요약은 기간과 확인 가능한 해소 건수를 반환한다")
    void documentsWeeklyResolutions() throws Exception {
        when(useCase.summarizeWeeklyResolutions(SCOPE, new Cursor(EventType.HANDOFF_BLOCKED, "handoff:a"), 1)).thenReturn(new BriefWeeklyResolutions(
                LocalDate.parse("2026-08-31"), ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-30T15:00:00Z"), Instant.parse("2026-09-06T15:00:00Z"), Instant.parse("2026-09-05T00:00:00Z"), 2L,
                List.of(new BriefWeeklyResolutions.Item(EventType.ROLE_UNASSIGNED, "role:a", Instant.parse("2026-09-04T00:00:00Z"), 2L)),
                new Cursor(EventType.ROLE_UNASSIGNED, "role:a")));
        mvc.perform(get(BriefAttentionController.RESOLUTIONS_PATH, TEAM, SEASON)
                        .param("afterEventType", "HANDOFF_BLOCKED").param("afterSourceReference", "handoff:a").param("limit", "1")
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedCount").value(2))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefWeeklyResolutions",
                        "시즌 시간대의 이번 주에 연속된 활성·해소 전환을 확인했고 현재도 해소 상태인 항목 수와 상세 목록을 중계한다.", "BRIEF 이번 주 해소 요약",
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 방식의 팀에서 사용하는 접근 키").optional()), headers(),
                        queryParameters(parameterWithName("afterEventType").optional().description("다음 페이지 커서 이벤트 타입. afterSourceReference와 함께 사용")
                                        .attributes(key("enumValues").value(Arrays.stream(EventType.values()).map(Enum::name).toList())),
                                parameterWithName("afterSourceReference").optional().description("다음 페이지 커서 원본 참조"),
                                parameterWithName("limit").optional().description("조회 크기 1~100, 기본 20")),
                        responseFields(fieldWithPath("weekStart").description("시즌 시간대의 이번 주 월요일"),
                                fieldWithPath("zoneId").description("시즌 IANA 시간대"),
                                fieldWithPath("windowStart").description("주간 시작 시각 이상"),
                                fieldWithPath("windowEnd").description("다음 주 시작 시각 미만"),
                                fieldWithPath("evaluatedAt").description("BRIEF 집계 확인 시각"),
                                fieldWithPath("resolvedCount").description("커서와 무관한 현재 전체 해소 항목 수. 누락 증거가 있는 항목 제외"),
                                fieldWithPath("items").description("복합 정체성 오름차순 해소 목록").attributes(key("itemsType").value("object")),
                                new EnumFields(EventType.class).withPath("items[].reasonCode").description("해소한 관심 항목 종류"),
                                fieldWithPath("items[].sourceReference").description("원본 참조"),
                                fieldWithPath("items[].resolvedAt").description("활성 다음 리비전에서 해소로 바뀐 원본 시각"),
                                fieldWithPath("items[].resolvedRevision").description("해소로 바뀐 원본 리비전"),
                                fieldWithPath("nextCursor").optional().description("다음 페이지 배타 커서. 끝이면 null"),
                                new EnumFields(EventType.class).withPath("nextCursor.eventType").description("커서 이벤트 타입"),
                                fieldWithPath("nextCursor.sourceReference").description("커서 원본 참조"))));
    }

    @Test
    @DisplayName("활성 관심 항목 요약과 조회 실패는 서로 다른 HTTP 결과로 반환한다")
    void documentsSummaryAndUnavailable() throws Exception {
        when(useCase.summarizeAttention(SCOPE)).thenReturn(new BriefAttentionSummary(2L, 3L, 1L));
        mvc.perform(get(BriefAttentionController.SUMMARY_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.highCount").value(2))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefAttentionSummary",
                        "권한을 확인한 팀·시즌의 활성 심각도별 개수와 공백 항목 수를 중계한다.", "BRIEF 관심 항목 요약",
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 방식의 팀에서 사용하는 접근 키").optional()),
                        headers(), responseFields(
                                fieldWithPath("highCount").description("활성 HIGH 항목 수"),
                                fieldWithPath("mediumCount").description("활성 MEDIUM 항목 수"),
                                fieldWithPath("revisionGapCount").description("공백 기록이 있는 활성 항목 수. 심각도별 개수와 중복됨"))));
        when(useCase.summarizeAttention(SCOPE)).thenThrow(new BriefIntegrationUnavailableException());
        mvc.perform(get(BriefAttentionController.SUMMARY_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("BRIEF_UNAVAILABLE"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefAttentionSummaryUnavailable",
                        "권한을 확인한 팀·시즌의 활성 심각도별 개수와 공백 항목 수를 중계한다.", "BRIEF 관심 항목 요약",
                        paths(), headers(), responseFields(fieldWithPath("code").description("오류 코드"),
                                fieldWithPath("message").description("사용자 안내"))));
    }

    @Test
    @DisplayName("관심 항목 필터와 배타 커서를 중계하고 반쪽 커서는 거부한다")
    void documentsFilteredPage() throws Exception {
        var filter = new Filter(Status.RESOLVED, Severity.HIGH, true,
                new Cursor(EventType.ROLE_UNASSIGNED, "role:1"), 1);
        var item = new Item(EventType.ROLE_UNASSIGNED, Severity.HIGH, "role:2", Status.RESOLVED,
                Instant.parse("2026-08-31T00:00:00Z"), 4L, 1, true);
        when(useCase.findAttentionItems(SCOPE, filter)).thenReturn(new BriefAttentionPage(
                List.of(item), new Cursor(item.reasonCode(), item.sourceReference())));
        mvc.perform(get(BriefAttentionController.LIST_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("status", "RESOLVED").param("severity", "HIGH").param("revisionGap", "true")
                        .param("afterEventType", "ROLE_UNASSIGNED").param("afterSourceReference", "role:1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].aggregateRevision").value(4))
                .andExpect(jsonPath("$.nextCursor.sourceReference").value("role:2"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefAttentionItems",
                        "상태·심각도·공백 조건의 현재 관심 항목을 키셋으로 중계한다. 조건 변경 시 커서를 초기화한다.", "BRIEF 관심 항목 목록",
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 방식의 팀에서 사용하는 접근 키").optional()), headers(),
                        queryParameters(
                                parameterWithName("status").optional().description("ACTIVE(기본) 또는 RESOLVED")
                                        .attributes(key("enumValues").value(List.of("ACTIVE", "RESOLVED"))),
                                parameterWithName("severity").optional().description("HIGH 또는 MEDIUM; 생략하면 전체")
                                        .attributes(key("enumValues").value(List.of("HIGH", "MEDIUM"))),
                                parameterWithName("revisionGap").optional().description("true 또는 false; 생략하면 전체"),
                                parameterWithName("afterEventType").optional().description("이전 페이지 커서의 eventType")
                                        .attributes(key("enumValues").value(Arrays.stream(EventType.values()).map(Enum::name).toList())),
                                parameterWithName("afterSourceReference").optional().description("이전 페이지 커서의 sourceReference. afterEventType과 함께 제공"),
                                parameterWithName("limit").optional().description("1~100, 기본 20")),
                        responseFields(fieldWithPath("items").description("현재 관심 항목"),
                                new EnumFields(EventType.class).withPath("items[].reasonCode").description("원본 신호 종류"),
                                new EnumFields(Severity.class).withPath("items[].severity").description("표시 심각도"),
                                fieldWithPath("items[].sourceReference").description("불투명 원본 참조"),
                                new EnumFields(Status.class).withPath("items[].status").description("현재 상태"),
                                fieldWithPath("items[].observedAt").description("원본 관측 UTC 시각"),
                                fieldWithPath("items[].aggregateRevision").description("적용한 원본 리비전"),
                                fieldWithPath("items[].ruleVersion").description("투영 규칙 버전"),
                                fieldWithPath("items[].revisionGap").description("누적 리비전 공백 기록 여부"),
                                fieldWithPath("nextCursor").optional().description("다음 커서, 마지막 페이지는 null"),
                                new EnumFields(EventType.class).withPath("nextCursor.eventType").description("커서 신호 종류"),
                                fieldWithPath("nextCursor.sourceReference").description("커서 원본 참조"))));
        mvc.perform(get(BriefAttentionController.LIST_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("afterEventType", "ROLE_UNASSIGNED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        when(useCase.findAttentionItems(SCOPE, new Filter(Status.ACTIVE, null, null, null, 20)))
                .thenReturn(new BriefAttentionPage(List.of(), null));
        mvc.perform(get(BriefAttentionController.LIST_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account())))
                .andExpect(status().isOk()).andExpect(content().json("{\"items\":[],\"nextCursor\":null}"));
    }

    @Test
    @DisplayName("적용 상태 전이와 공백 발견 기록을 중계하고 잘못된 조회 조건은 거부한다")
    void documentsTransitions() throws Exception {
        var query = new BriefAttentionTransitions.Query(EventType.ROLE_UNASSIGNED, "role:+& 한글", 9L, 1);
        when(useCase.findAttentionTransitions(SCOPE, query)).thenReturn(new BriefAttentionTransitions(
                List.of(new BriefAttentionTransitions.Transition(REQUEST, 7L, Status.RESOLVED,
                        Instant.parse("2026-08-31T00:00:00Z"), true, BriefAttentionTransitions.SourceSeverity.WARNING)), 7L));
        mvc.perform(get(BriefAttentionController.TRANSITIONS_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("eventType", "ROLE_UNASSIGNED").param("sourceReference", "role:+& 한글")
                        .param("beforeAggregateRevision", "9").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transitions[0].aggregateRevision").value(7))
                .andExpect(jsonPath("$.transitions[0].state").value("RESOLVED"))
                .andExpect(jsonPath("$.transitions[0].detectedRevisionGap").value(true))
                .andExpect(jsonPath("$.transitions[0].sourceSeverity").value("WARNING"))
                .andExpect(jsonPath("$.nextBeforeAggregateRevision").value(7))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefAttentionTransitions",
                        "같은 관심 항목에 적용된 상태 전이를 원본 리비전 역순으로 중계한다.", "BRIEF 관심 항목 상태 전이",
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 방식의 팀에서 사용하는 접근 키").optional()), headers(),
                        queryParameters(parameterWithName("eventType").description("관심 항목의 원본 신호 종류"),
                                parameterWithName("sourceReference").description("관심 항목의 불투명 원본 참조"),
                                parameterWithName("beforeAggregateRevision").optional().description("이 리비전보다 작은 과거 전이"),
                                parameterWithName("limit").optional().description("1~100, 기본 20")),
                        responseFields(fieldWithPath("transitions").description("실제 적용 전이 목록"),
                                fieldWithPath("transitions[].eventId").description("전이를 만든 원본 이벤트 UUID"),
                                fieldWithPath("transitions[].aggregateRevision").description("적용한 원본 리비전"),
                                new EnumFields(Status.class).withPath("transitions[].state").description("전이의 원본 상태"),
                                fieldWithPath("transitions[].observedAt").description("원본 관측 UTC 시각"),
                                fieldWithPath("transitions[].detectedRevisionGap").description("이 전이에서 새로 공백을 발견했는지 여부"),
                                new EnumFields(BriefAttentionTransitions.SourceSeverity.class).withPath("transitions[].sourceSeverity")
                                        .optional().description("전이에 저장된 원본 심각도. v1은 null"),
                                fieldWithPath("nextBeforeAggregateRevision").optional().description("다음 과거 페이지 커서, 마지막은 null"))));
        var first = new BriefAttentionTransitions.Query(EventType.ROLE_UNASSIGNED, "role:+& 한글", null, 20);
        when(useCase.findAttentionTransitions(SCOPE, first)).thenReturn(new BriefAttentionTransitions(List.of(), null));
        mvc.perform(get(BriefAttentionController.TRANSITIONS_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("eventType", "ROLE_UNASSIGNED").param("sourceReference", "role:+& 한글"))
                .andExpect(status().isOk()).andExpect(content().json("{\"transitions\":[],\"nextBeforeAggregateRevision\":null}"));
        mvc.perform(get(BriefAttentionController.TRANSITIONS_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("eventType", "ROLE_UNASSIGNED").param("sourceReference", "role:1").param("beforeAggregateRevision", "0"))
                .andExpect(status().isBadRequest());
        when(useCase.findAttentionTransitions(SCOPE, first)).thenThrow(new BriefAttentionQueryRejectedException());
        mvc.perform(get(BriefAttentionController.TRANSITIONS_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account()))
                        .param("eventType", "ROLE_UNASSIGNED").param("sourceReference", "role:+& 한글"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private Snippet paths() {
        return pathParameters(parameterWithName("teamId").description("팀 UUID"), parameterWithName("seasonId").description("시즌 UUID"));
    }

    private Snippet headers() {
        return responseHeaders(headerWithName(RequestIdFilter.HEADER_NAME).description("요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL).description("민감 응답 캐시 금지"));
    }

    private UsernamePasswordAuthenticationToken account() {
        return UsernamePasswordAuthenticationToken.authenticated(new Principal(ACCOUNT), null, List.of());
    }

    private record Principal(UUID accountId) implements AuthenticatedAccountPrincipal {
        @Override
        public long sessionVersion() { return 0; }
    }
}
