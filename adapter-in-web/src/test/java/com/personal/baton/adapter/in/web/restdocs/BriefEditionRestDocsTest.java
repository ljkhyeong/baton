package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefEditionController;
import com.personal.baton.adapter.in.web.brief.BriefWorkspaceContextController;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefEditionComparison;
import com.personal.baton.application.brief.BriefEditionDeliveryStatus;
import com.personal.baton.application.brief.BriefEditionHistory;
import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.BriefGenerationReadiness;
import com.personal.baton.application.brief.BriefSourceContext;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase.Scope;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerationResult;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionResult;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefWorkspaceContextUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders;
import org.springframework.restdocs.payload.FieldDescriptor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



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
    private static final String ETAG = "\"brief-edition-v2-test\"";

    private BriefEditionUseCase briefEditionUseCase;
    private MockMvc mockMvc;
    private BriefWorkspaceContextUseCase contextUseCase;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        briefEditionUseCase = mock(BriefEditionUseCase.class);
        contextUseCase = mock(BriefWorkspaceContextUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BriefEditionController(briefEditionUseCase), new BriefWorkspaceContextController(contextUseCase)
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
    @DisplayName("선택한 브리프의 지난주 마지막 에디션과 ETag를 반환한다")
    void documentsPreviousWeekEdition() throws Exception {
        when(briefEditionUseCase.findPreviousWeekEdition(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY), EDITION_ID))
                .thenReturn(new LatestEditionResult(edition(), ETAG));
        mockMvc.perform(RestDocumentationRequestBuilders.get(BriefEditionController.PREVIOUS_WEEK_PATH, TEAM_ID, SEASON_ID, EDITION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getPreviousWeekBriefEdition",
                        "선택한 브리프와 같은 시간대의 정확한 지난주 마지막 에디션을 조회한다. 없으면 404이며 다른 주차로 대체하지 않는다.", "BRIEF 지난주 에디션 조회",
                        pathParameters(parameterWithName("teamId").description("팀 UUID"), parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("editionId").description("비교 대상 브리프 UUID")),
                        readHeaders(), editionResponseHeaders(), editionResponseFields()));
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

        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefEditionController.LATEST_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(jsonPath("$.editionId").value(EDITION_ID.toString()))
                .andExpect(jsonPath("$.items[0].section").value("CURRENT_WEEK"))
                .andExpect(jsonPath("$.items[1].section").value("CARRY_OVER"))
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

        mockMvc.perform(RestDocumentationRequestBuilders
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

    private Snippet scopedPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("BATON 팀 UUID"),
                parameterWithName("seasonId").description("BATON 시즌 UUID")
        );
    }

    private Snippet readHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("대상 워크스페이스 접근 키").optional()
        );
    }

    private Snippet generationHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("대상 워크스페이스 접근 키").optional(),
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 출처와 정확히 같은 브라우저 출처"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName("X-CSRF-TOKEN")
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF 토큰")
        );
    }

    private Snippet editionResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL).description("민감 응답 캐시 금지"),
                headerWithName(HttpHeaders.ETAG).description("BRIEF 불변 에디션 검증자")
        );
    }

    private Snippet generationResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL).description("민감 응답 캐시 금지"),
                headerWithName(HttpHeaders.ETAG).description("생성 결과 BRIEF 에디션 검증자"),
                headerWithName(HttpHeaders.LOCATION).description("최신 BRIEF 에디션 조회 경로")
        );
    }

    private Snippet editionResponseFields() {
        var fields = new ArrayList<FieldDescriptor>(List.of(
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
                fieldWithPath("items").description("불변 에디션 항목 목록")

        ));
        fields.addAll(itemFields("items[]."));
        return responseFields(fields);
    }

    @Test
    @DisplayName("에디션 이력은 과거 방향 커서와 저장된 요약을 제공한다")
    void documentsHistory() throws Exception {
        when(briefEditionUseCase.findEditionHistory(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY),
                new BriefEditionHistory.Query(4L, 1))).thenReturn(new BriefEditionHistory(List.of(summary()), 3L));
        var fields = new ArrayList<FieldDescriptor>();
        fields.add(fieldWithPath("editions").description("생성 순번 내림차순의 저장된 브리프"));
        fields.addAll(summaryFields("editions[]."));
        fields.add(fieldWithPath("nextBeforeGeneration").optional().description("다음 과거 페이지의 배타 커서. 마지막은 null"));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefEditionController.GENERATION_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication()))
                        .param("beforeGeneration", "4").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextBeforeGeneration").value(3))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefEditionHistory", "권한 범위의 저장된 브리프를 과거 방향으로 조회한다.", "BRIEF 이력 조회",
                        scopedPathParameters(), readHeaders(), queryParameters(
                                parameterWithName("beforeGeneration").optional().description("이 생성 순번 미만, 양수"),
                                parameterWithName("limit").optional().description("1~100, 기본 20")), responseFields(fields)));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefEditionController.GENERATION_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())).param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("선택한 에디션은 불변 본문과 ETag를 유지한다")
    void documentsSelectedEdition() throws Exception {
        when(briefEditionUseCase.findEdition(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY), EDITION_ID))
                .thenReturn(new LatestEditionResult(edition(), ETAG));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefEditionController.EDITION_PATH, TEAM_ID, SEASON_ID, EDITION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefEdition", "선택한 브리프의 팀·시즌 권한을 확인하고 고정된 내용을 반환한다.", "BRIEF 단건 조회",
                        editionPaths(), readHeaders(), editionResponseHeaders(), editionResponseFields()));
    }

    @Test
    @DisplayName("브리프 비교는 추가·제외·변경과 이전 분류를 그대로 중계한다")
    void documentsComparison() throws Exception {
        var before = edition().items().getFirst();
        var after = new BriefEditionSnapshot.Item(before.sourceReference(), before.reasonCode(), before.severity(), before.status(),
                before.observedAt(), before.ruleVersion(), before.aggregateRevision(), before.revisionGap(), BriefEditionSnapshot.Section.CARRY_OVER);
        when(briefEditionUseCase.compareEditions(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY), ACCOUNT_ID, EDITION_ID))
                .thenReturn(new BriefEditionComparison(new BriefEditionHistory.Summary(ACCOUNT_ID, 2, summary().weekStart(), summary().zoneId(),
                        summary().generatedAt(), 16, 2, 2), summary(), List.of(before), List.of(before), List.of(new BriefEditionComparison.Change(before, after))));
        var fields = new ArrayList<FieldDescriptor>();
        fields.add(fieldWithPath("from").description("비교 기준 브리프")); fields.addAll(summaryFields("from."));
        fields.add(fieldWithPath("to").description("비교 대상 브리프")); fields.addAll(summaryFields("to."));
        fields.add(fieldWithPath("added").description("대상에만 포함된 항목")); fields.addAll(itemFields("added[]."));
        fields.add(fieldWithPath("removed").description("대상에서 제외된 항목. 해소 판정이 아님")); fields.addAll(itemFields("removed[]."));
        fields.add(fieldWithPath("changed").description("고정 필드가 달라진 항목"));
        fields.add(fieldWithPath("changed[].before").description("기준에 저장된 항목")); fields.addAll(itemFields("changed[].before."));
        fields.add(fieldWithPath("changed[].after").description("대상에 저장된 항목")); fields.addAll(itemFields("changed[].after."));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefEditionController.COMPARISON_PATH, TEAM_ID, SEASON_ID, EDITION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())).param("fromEditionId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed[0].after.section").value("CARRY_OVER"))
                .andDo(MockMvcRestDocumentationWrapper.document("compareBriefEditions", "양쪽 브리프의 팀·시즌 권한을 확인하고 저장된 차이만 중계한다.", "BRIEF 비교",
                        editionPaths(), readHeaders(), queryParameters(parameterWithName("fromEditionId").description("기준 브리프 UUID")), responseFields(fields)));
    }

    @Test
    @DisplayName("현재 업무 정보는 불변 브리프와 분리해 조회한다")
    void documentsSources() throws Exception {
        var identity = new BriefSourceContext.Identity(BriefAttentionPage.EventType.ROUTINE_REPEATEDLY_OVERDUE, "baton-continuity:" + EDITION_ID);
        when(contextUseCase.resolveSources(new Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY), List.of(identity)))
                .thenReturn(List.of(new BriefSourceContext(identity, new BriefSourceContext.Target("주간 홍보 점검", EXECUTION_ID, ACCOUNT_ID, false))));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .post(BriefWorkspaceContextController.SOURCES_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication()))
                        .header(HttpHeaders.ORIGIN, "https://baton.example").header("Sec-Fetch-Site", "same-origin").header("X-CSRF-TOKEN", "opaque-csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sources\":[{\"eventType\":\"ROUTINE_REPEATEDLY_OVERDUE\",\"sourceReference\":\"baton-continuity:" + EDITION_ID + "\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].target.title").value("주간 홍보 점검"))
                .andDo(MockMvcRestDocumentationWrapper.document("queryBriefSources", "요청한 원본 참조를 같은 팀·시즌의 현재 역할·루틴에 연결한다. 연결할 수 없으면 target은 null이다.", "BRIEF 현재 업무 정보",
                        scopedPathParameters(), generationHeaders(), requestFields(
                                new ConstrainedFields(BriefWorkspaceContextController.SourcesRequest.class).withPath("sources").description("현재 표시할 원본 참조 1~100건"),
                                new EnumFields(BriefAttentionPage.EventType.class).withPath("sources[].eventType").description("신호 유형"),
                                new ConstrainedFields(BriefWorkspaceContextController.SourceRequest.class).addConstraints(fieldWithPath("sources[].sourceReference"), "sourceReference").description("빈 값이 아닌 원본 참조, 최대 512자")),
                        responseFields(fieldWithPath("sources").description("요청 순서의 현재 업무 정보"),
                                new EnumFields(BriefAttentionPage.EventType.class).withPath("sources[].eventType").description("요청한 신호 유형"),
                                fieldWithPath("sources[].sourceReference").description("요청한 원본 참조"),
                                fieldWithPath("sources[].target").optional().description("현재 업무. 이전 참조·삭제·범위 불일치는 null"),
                                fieldWithPath("sources[].target.title").description("현재 업무 이름. 브리프 생성 당시 이름이 아님"),
                                fieldWithPath("sources[].target.roleId").description("같은 팀·시즌 역할 UUID"),
                                fieldWithPath("sources[].target.routineId").optional().description("루틴이면 UUID, 역할이면 null"),
                                fieldWithPath("sources[].target.archived").description("현재 루틴 보관 여부"))));
    }

    @Test
    @DisplayName("생성 준비 상태는 전달 대기·실패와 확인 시각을 반환한다")
    void documentsReadiness() throws Exception {
        when(contextUseCase.findGenerationReadiness(new Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY)))
                .thenReturn(new BriefGenerationReadiness(BriefGenerationReadiness.Status.DELIVERY_FAILED, 2, 1, edition().generatedAt(), edition().generatedAt()));
        mockMvc.perform(RestDocumentationRequestBuilders
                        .get(BriefWorkspaceContextController.READINESS_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefGenerationReadiness", "현재 시즌의 전달 기록과 이번 주 생성 실행을 읽는다. 생성 시 다시 확인하며 BRIEF 연결 성공을 보장하지 않는다.", "BRIEF 생성 준비 상태",
                        scopedPathParameters(), readHeaders(), responseFields(
                                new EnumFields(BriefGenerationReadiness.Status.class).withPath("status").description("전달·생성 요청 준비 상태"),
                                fieldWithPath("pendingCount").description("전달 대기·진행 중인 이벤트 수"),
                                fieldWithPath("failedCount").description("전달 영구 실패 이벤트 수"),
                                fieldWithPath("lastDeliveredAt").optional().description("마지막 전달 성공 시각. 성공 기록이 없으면 null"),
                                fieldWithPath("checkedAt").description("BATON 확인 UTC 시각"))));
    }

    @Test
    @DisplayName("브리프 추가 전달 조회는 선택한 에디션과 상태·확인 시각을 별도 응답으로 반환한다")
    void documentsEditionDeliveryStatus() throws Exception {
        when(briefEditionUseCase.findEditionDeliveryStatus(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY), EDITION_ID))
                .thenReturn(new BriefEditionDeliveryStatus(EDITION_ID, BriefEditionDeliveryStatus.Status.ADDITIONAL_DELIVERIES, edition().generatedAt()));
        mockMvc.perform(RestDocumentationRequestBuilders.get(BriefEditionController.DELIVERY_STATUS_PATH, TEAM_ID, SEASON_ID, EDITION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY).with(authentication(accountAuthentication())))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.editionId").value(EDITION_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document("getBriefEditionDeliveryStatus",
                        "선택한 에디션의 권한을 확인하고 BATON의 마지막 성공 생성·재사용 경계 이후 추가 전달 완료 기록을 조회한다. 근거가 없으면 UNKNOWN이다.", "BRIEF 추가 전달 확인",
                        editionPaths(), readHeaders(), responseFields(
                                fieldWithPath("editionId").description("확인한 불변 에디션 UUID"),
                                new EnumFields(BriefEditionDeliveryStatus.Status.class).withPath("status").description("추가 전달 있음·없음 또는 확인 근거 없음"),
                                fieldWithPath("checkedAt").description("BATON 확인 UTC 시각"))));
    }

    private Snippet editionPaths() {
        return pathParameters(parameterWithName("teamId").description("팀 UUID"), parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("editionId").description("선택한 브리프 UUID"));
    }

    private List<FieldDescriptor> summaryFields(String prefix) {
        return List.of(fieldWithPath(prefix + "editionId").description("불변 에디션 UUID"),
                fieldWithPath(prefix + "generation").description("시즌 안에서 증가하는 생성 순번"),
                fieldWithPath(prefix + "weekStart").description("주간 시작 월요일"),
                fieldWithPath(prefix + "zoneId").description("저장된 IANA 시간대"),
                fieldWithPath(prefix + "generatedAt").description("생성 UTC 시각"),
                fieldWithPath(prefix + "sourceCursor").description("BRIEF 로컬 수신 경계"),
                fieldWithPath(prefix + "ruleVersion").description("선정 규칙 버전"),
                fieldWithPath(prefix + "itemCount").description("고정된 항목 수"));
    }

    private BriefEditionHistory.Summary summary() {
        var edition = edition();
        return new BriefEditionHistory.Summary(edition.editionId(), edition.generation(), edition.weekStart(), edition.zoneId(),
                edition.generatedAt(), edition.sourceCursor(), edition.ruleVersion(), edition.items().size());
    }

    private List<FieldDescriptor> itemFields(String prefix) {
        return List.of(
                fieldWithPath(prefix + "sourceReference").description("BATON 신호의 안정적인 원본 참조"),
                fieldWithPath(prefix + "reasonCode").description("BATON 연속성 신호 유형"),
                fieldWithPath(prefix + "severity").description("BRIEF 표시 심각도"),
                fieldWithPath(prefix + "status").description("생성 시점 신호 상태"),
                fieldWithPath(prefix + "observedAt").description("원본 상태 관찰 시각"),
                fieldWithPath(prefix + "ruleVersion").description("항목 투영 규칙 버전"),
                fieldWithPath(prefix + "aggregateRevision").optional().description("원본 신호 집계 리비전. 이전 에디션의 미기록 값은 null"),
                fieldWithPath(prefix + "revisionGap").optional().description("생성 시점 누적 리비전 공백 여부. 이전 에디션의 미기록 값은 null"),
                new EnumFields(BriefEditionSnapshot.Section.class).withPath(prefix + "section").optional()
                        .description("생성 당시 이번 주 변경 또는 이전 미해소 분류. 이전 에디션은 null")
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
                2,
                List.of(new BriefEditionSnapshot.Item(
                        "baton-continuity:00000000-0000-0000-0000-000000002646",
                        "ROLE_UNASSIGNED",
                        "MEDIUM",
                        "ACTIVE",
                        Instant.parse("2026-08-28T03:00:00Z"),
                        1,
                        2L,
                        false,
                        BriefEditionSnapshot.Section.CURRENT_WEEK
                ), new BriefEditionSnapshot.Item("routine:carry-over", "ROUTINE_MISSED", "MEDIUM", "ACTIVE",
                        Instant.parse("2026-08-21T03:00:00Z"), 1, 1L, false, BriefEditionSnapshot.Section.CARRY_OVER))
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
