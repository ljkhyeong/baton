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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefEditionController;
import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerationResult;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
class BriefEditionRestDocsTest {

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

    private BriefEditionUseCase briefEditionUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        briefEditionUseCase = mock(BriefEditionUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BriefEditionController(briefEditionUseCase)
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

    @DisplayName("BRIEF 최신 에디션 API는 권한 범위의 불변 에디션과 ETag를 반환한다")
    @Test
    void documentsLatestEdition() throws Exception {
        when(briefEditionUseCase.findLatestEdition(new LatestEditionQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        ))).thenReturn(new LatestEditionResult(edition(), ETAG));

        mockMvc.perform(org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders
                        .get(BriefEditionController.LATEST_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(jsonPath("$.editionId").value(EDITION_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getLatestBriefEdition",
                        "인증된 BATON 계정의 활성 팀 멤버십과 워크스페이스 접근 키를 확인한 뒤 BRIEF 최신 불변 에디션을 중계한다.",
                        "BRIEF 최신 에디션 조회",
                        scopedPathParameters(),
                        readHeaders(),
                        editionResponseHeaders(),
                        editionResponseFields()
                ));
    }

    @DisplayName("BRIEF 에디션 생성 API는 BATON 실행 기록과 생성 결과를 반환한다")
    @Test
    void documentsEditionGeneration() throws Exception {
        when(briefEditionUseCase.generateEdition(new GenerateEditionCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        ))).thenReturn(new GenerationResult(
                EXECUTION_ID,
                17,
                EDITION_ID,
                3,
                17,
                ETAG,
                true
        ));

        mockMvc.perform(org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders
                        .post(BriefEditionController.GENERATION_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .header(HttpHeaders.ORIGIN, "https://baton.example")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("X-CSRF-TOKEN", "opaque-csrf-token")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "generateBriefEdition",
                        "BATON이 시즌 시간대의 현재 주차와 완료된 BRIEF 이벤트 전달 watermark를 실행 기록에 고정하고 BRIEF 에디션 생성을 호출한다.",
                        "BRIEF 에디션 생성",
                        scopedPathParameters(),
                        generationHeaders(),
                        generationResponseHeaders(),
                        responseFields(
                                fieldWithPath("executionId").description("BATON의 내구성 있는 생성 실행 UUID"),
                                fieldWithPath("deliveryWatermark").description("생성 전에 완료를 확인한 BATON BRIEF outbox 최대 ID"),
                                fieldWithPath("editionId").description("BRIEF가 반환한 불변 에디션 UUID"),
                                fieldWithPath("generation").description("작업공간·시즌 범위 에디션 세대"),
                                fieldWithPath("sourceCursor").description("BRIEF 로컬 수신 순서 cursor"),
                                fieldWithPath("created").description("새 에디션을 만들었으면 true, 직전 상태를 재사용했으면 false")
                        )
                ));
    }

    private org.springframework.restdocs.snippet.Snippet scopedPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("BATON 팀 UUID"),
                parameterWithName("seasonId").description("BATON 시즌 UUID")
        );
    }

    private org.springframework.restdocs.snippet.Snippet readHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("대상 워크스페이스 접근 키")
        );
    }

    private org.springframework.restdocs.snippet.Snippet generationHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("대상 워크스페이스 접근 키"),
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 출처와 정확히 같은 브라우저 출처"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName("X-CSRF-TOKEN")
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF 토큰")
        );
    }

    private org.springframework.restdocs.snippet.Snippet editionResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL).description("민감 응답 캐시 금지"),
                headerWithName(HttpHeaders.ETAG).description("BRIEF 불변 에디션 검증자")
        );
    }

    private org.springframework.restdocs.snippet.Snippet generationResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL).description("민감 응답 캐시 금지"),
                headerWithName(HttpHeaders.ETAG).description("생성 결과 BRIEF 에디션 검증자"),
                headerWithName(HttpHeaders.LOCATION).description("최신 BRIEF 에디션 조회 경로")
        );
    }

    private org.springframework.restdocs.snippet.Snippet editionResponseFields() {
        return responseFields(
                fieldWithPath("editionId").description("불변 에디션 UUID"),
                fieldWithPath("workspaceId").description("BATON 팀 UUID와 같은 BRIEF 작업공간 UUID"),
                fieldWithPath("seasonId").description("BATON 시즌 UUID"),
                fieldWithPath("generation").description("작업공간·시즌 범위 에디션 세대"),
                fieldWithPath("weekStart").description("시즌 시간대 기준 월요일"),
                fieldWithPath("zoneId").description("BATON 시즌 IANA 시간대"),
                fieldWithPath("windowStart").description("주간 구간 시작 UTC 시각"),
                fieldWithPath("windowEnd").description("주간 구간 종료 UTC 시각"),
                fieldWithPath("sourceCursor").description("BRIEF 로컬 수신 순서 cursor"),
                fieldWithPath("generatedAt").description("에디션 생성 UTC 시각"),
                fieldWithPath("ruleVersion").description("BRIEF 선정 규칙 버전"),
                fieldWithPath("items").description("불변 에디션 항목 목록"),
                fieldWithPath("items[].sourceReference").description("BATON 신호의 안정적인 원본 참조"),
                fieldWithPath("items[].reasonCode").description("BATON 연속성 신호 유형"),
                fieldWithPath("items[].severity").description("BRIEF 표시 심각도"),
                fieldWithPath("items[].status").description("생성 시점 신호 상태"),
                fieldWithPath("items[].observedAt").description("원본 상태 관찰 시각"),
                fieldWithPath("items[].ruleVersion").description("항목 투영 규칙 버전"),
                fieldWithPath("items[].aggregateRevision").description("원본 신호 집계 리비전"),
                fieldWithPath("items[].revisionGap").description("생성 시점 누적 리비전 공백 여부")
        );
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TestAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private BriefEditionSnapshot edition() {
        return new BriefEditionSnapshot(
                EDITION_ID,
                TEAM_ID,
                SEASON_ID,
                3,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-23T15:00:00Z"),
                Instant.parse("2026-08-30T15:00:00Z"),
                17,
                Instant.parse("2026-08-29T03:00:00Z"),
                1,
                List.of(new BriefEditionSnapshot.Item(
                        "baton-continuity:00000000-0000-0000-0000-000000002646",
                        "ROLE_UNASSIGNED",
                        "MEDIUM",
                        "ACTIVE",
                        Instant.parse("2026-08-28T03:00:00Z"),
                        1,
                        2L,
                        false
                ))
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
