package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResourceHealthController;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.WatchCheckOutcome;
import com.personal.baton.application.workspace.error.ResourceCheckRequestException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class ResourceHealthRestDocsTest {
    private static final UUID TEAM = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEASON = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RESOURCE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String KEY = "workspace-access-key";
    private static final String PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}";
    private static final String READ_DESCRIPTION = "공유 접근 키와 자료 소유권을 확인한 뒤 자료 화면과 별도로 현재 연결 상태를 조회한다.";
    private static final String CHECK_DESCRIPTION = "공유 접근 키와 자료 소유권을 확인한 뒤 비동기 URL 재점검을 접수한다. 완료 결과가 아니다.";
    private InspectResourceHealthUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider docs) {
        useCase = mock(InspectResourceHealthUseCase.class);
        mvc = standaloneSetup(new WorkspaceResourceHealthController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter(() -> TEAM))
                .alwaysExpect(header().string("X-Request-ID", TEAM.toString()))
                .apply(documentationConfiguration(docs)).build();
    }

    @ParameterizedTest
    @EnumSource(Availability.class)
    @DisplayName("자료 연결 상태는 최근 실패 원인과 횟수를 반환하며 확인 불가 결과에는 null을 명시한다")
    void readHealth(Availability availability) throws Exception {
        Instant checked = availability == Availability.AVAILABLE ? Instant.parse("2026-09-05T01:00:00Z") : null;
        var health = availability == Availability.AVAILABLE ? WatchResourceHealth.BROKEN : WatchResourceHealth.UNKNOWN;
        var outcome = availability == Availability.AVAILABLE ? WatchCheckOutcome.DNS_FAILURE : null;
        Integer failures = availability == Availability.AVAILABLE ? 3 : null;
        when(useCase.inspect(TEAM, SEASON, RESOURCE, KEY))
                .thenReturn(new Result(RESOURCE, health, availability, checked, checked != null, outcome, failures));
        mvc.perform(get(PATH + "/health", TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.resourceId").value(RESOURCE.toString()))
                .andExpect(jsonPath("$.health").value(health.name()))
                .andExpect(jsonPath("$.availability").value(availability.name()))
                .andExpect(jsonPath("$.checkRequestAllowed").value(checked != null))
                .andExpect(jsonPath("$.lastCheckedAt").value(checked == null ? null : checked.toString()))
                .andExpect(jsonPath("$.lastOutcome").value(outcome == null ? null : outcome.name()))
                .andExpect(jsonPath("$.consecutiveFailures").value(failures))
                .andDo(MockMvcRestDocumentationWrapper.document("inspectResourceHealth", READ_DESCRIPTION, "자료 연결 상태 조회",
                        pathParameters(parameterWithName("teamId").description("팀 UUID"), parameterWithName("seasonId").description("시즌 UUID"), parameterWithName("resourceId").description("자료 UUID")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("팀 공유 접근 키")),
                        responseHeaders(headerWithName("X-Request-ID").description("서버 요청 진단 UUID"), headerWithName("Cache-Control").description("응답 저장 금지")),
                        responseFields(fieldWithPath("resourceId").description("요청한 자료 UUID"),
                                new EnumFields(WatchResourceHealth.class).withPath("health").description("WATCH 도달 가능성 상태"),
                                new EnumFields(Availability.class).withPath("availability").description("조회 결과의 최신성 및 감시 여부"),
                                fieldWithPath("lastCheckedAt").type(JsonFieldType.STRING).optional().description("최근 점검 UTC 시각. 결과가 없으면 null"),
                                new EnumFields(WatchCheckOutcome.class).withPath("lastOutcome").optional().description("최근 WATCH 점검 결과 코드. 최신 결과가 없으면 null"),
                                fieldWithPath("consecutiveFailures").type(JsonFieldType.NUMBER).optional().description("연속된 확정적 연결 실패 횟수. 0 이상의 정수이며 최신 결과가 없으면 null"),
                                fieldWithPath("checkRequestAllowed").description("현재 자료의 재점검 접수 가능 여부"))));
    }

    @ParameterizedTest
    @EnumSource(CheckStatus.class)
    @DisplayName("재점검 접수는 새 예약과 기존 예약 또는 실행 중 합류를 202로 구분한다")
    void requestCheck(CheckStatus status) throws Exception {
        when(useCase.requestCheck(TEAM, SEASON, RESOURCE, KEY)).thenReturn(new CheckResult(RESOURCE, status));
        mvc.perform(post(PATH + "/check-requests", TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", KEY))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.resourceId").value(RESOURCE.toString()))
                .andExpect(jsonPath("$.status").value(status.name()))
                .andDo(MockMvcRestDocumentationWrapper.document("requestResourceCheck", CHECK_DESCRIPTION, "자료 재점검 접수",
                        pathParameters(parameterWithName("teamId").description("팀 UUID"), parameterWithName("seasonId").description("시즌 UUID"), parameterWithName("resourceId").description("자료 UUID")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("팀 공유 접근 키")),
                        responseHeaders(headerWithName("X-Request-ID").description("서버 요청 진단 UUID"), headerWithName("Cache-Control").description("응답 저장 금지")),
                        responseFields(fieldWithPath("resourceId").description("요청한 자료 UUID"),
                                new EnumFields(CheckStatus.class).withPath("status").description("점검 접수 상태"))));
    }

    @ParameterizedTest
    @EnumSource(ResourceCheckRequestException.Reason.class)
    @DisplayName("재점검 불가와 요청 간격 제한 및 WATCH 장애를 409와 429 및 503으로 반환한다")
    void checkErrors(ResourceCheckRequestException.Reason reason) throws Exception {
        when(useCase.requestCheck(TEAM, SEASON, RESOURCE, KEY)).thenThrow(new ResourceCheckRequestException(reason,
                reason == ResourceCheckRequestException.Reason.RATE_LIMITED ? 17L : null));
        int expected = switch (reason) { case INACTIVE -> 409; case RATE_LIMITED -> 429; case UNAVAILABLE -> 503; };
        var result = mvc.perform(post(PATH + "/check-requests", TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", KEY))
                .andExpect(status().is(expected)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("WATCH_CHECK_" + reason.name()));
        if (reason == ResourceCheckRequestException.Reason.RATE_LIMITED) result.andExpect(header().string("Retry-After", "17"));
        result.andDo(MockMvcRestDocumentationWrapper.document("requestResourceCheck" + reason.name(), CHECK_DESCRIPTION, "자료 재점검 접수",
                responseHeaders(headerWithName("X-Request-ID").description("서버 요청 진단 UUID"), headerWithName("Cache-Control").description("응답 저장 금지"),
                        headerWithName("Retry-After").optional().description("다음 요청까지 기다릴 초")),
                responseFields(fieldWithPath("code").description("오류 코드"), fieldWithPath("message").description("사용자 안내"))));
    }

    @Test
    @DisplayName("공유 키 거부와 자료 없음은 WATCH 조회 상태로 숨기지 않고 403 및 404를 반환한다")
    void accessErrors() throws Exception {
        when(useCase.inspect(TEAM, SEASON, RESOURCE, KEY)).thenThrow(new WorkspaceAccessDeniedException());
        mvc.perform(get(PATH + "/health", TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", KEY))
                .andExpect(status().isForbidden())
                .andDo(MockMvcRestDocumentationWrapper.document("inspectResourceHealthForbidden", READ_DESCRIPTION, "자료 연결 상태 조회",
                        responseHeaders(headerWithName("X-Request-ID").description("서버 요청 진단 UUID")),
                        responseFields(fieldWithPath("code").description("오류 코드"), fieldWithPath("message").description("사용자 안내"))));
        doThrow(new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다")).when(useCase).inspect(TEAM, SEASON, RESOURCE, KEY);
        mvc.perform(get(PATH + "/health", TEAM, SEASON, RESOURCE).header("X-Baton-Access-Key", KEY))
                .andExpect(status().isNotFound())
                .andDo(MockMvcRestDocumentationWrapper.document("inspectResourceHealthNotFound", READ_DESCRIPTION, "자료 연결 상태 조회",
                        responseHeaders(headerWithName("X-Request-ID").description("서버 요청 진단 UUID")),
                        responseFields(fieldWithPath("code").description("오류 코드"), fieldWithPath("message").description("사용자 안내"))));
    }
}
