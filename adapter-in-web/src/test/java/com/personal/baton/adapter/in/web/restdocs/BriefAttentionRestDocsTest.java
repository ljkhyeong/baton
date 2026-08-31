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
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase;
import java.time.Instant;
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
    @DisplayName("활성 관심 항목 요약과 조회 실패는 서로 다른 HTTP 결과로 반환한다")
    void documentsSummaryAndUnavailable() throws Exception {
        when(useCase.summarizeAttention(SCOPE)).thenReturn(new BriefAttentionSummary(2L, 3L, 1L));
        mvc.perform(get(BriefAttentionController.SUMMARY_PATH, TEAM, SEASON)
                        .header("X-Baton-Access-Key", "access-key").with(authentication(account())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.highCount").value(2))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefAttentionSummary",
                        "권한을 확인한 팀·시즌의 활성 심각도별 개수와 공백 항목 수를 중계한다.", "BRIEF 관심 항목 요약",
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키")),
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
                        paths(), requestHeaders(headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키")), headers(),
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
    }
}
