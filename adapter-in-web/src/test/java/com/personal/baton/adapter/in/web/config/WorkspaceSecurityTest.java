package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import jakarta.servlet.DispatcherType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkspaceController.class)
@Import({SecurityConfig.class, WebFilterConfig.class})
class WorkspaceSecurityTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROUTINE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROUND_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID EXECUTION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-security-0001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceUseCase workspaceUseCase;

    @DisplayName("워크스페이스 생성 경로는 사용자 인증 세션과 CSRF 토큰 없이 호출할 수 있다")
    @Test
    void permitsWorkspaceCreationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.createWorkspace(eq(IDEMPOTENCY_KEY), isNull(), any(CreateWorkspaceCommand.class)))
                .thenReturn(new WorkspaceUseCase.CreatedWorkspaceResult(TEAM_ID, SEASON_ID, "access-key"));

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessKey").value("access-key"));
    }

    @DisplayName("접근 키 복구 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 운영자 키 검증으로 진입한다")
    @Test
    void permitsAccessKeyRecoveryWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.recoverAccessKey(TEAM_ID, SEASON_ID, IDEMPOTENCY_KEY, "recovery-key"))
                .thenReturn(new WorkspaceUseCase.AccessKeyResult("new-access-key"));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Recovery-Key", "recovery-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessKey").value("new-access-key"));
    }

    @DisplayName("워크스페이스 조회 경로는 사용자 인증 세션 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsScopedWorkspaceReadWithoutAuthentication() throws Exception {
        when(workspaceUseCase.getWorkspace(TEAM_ID, SEASON_ID, "access-key"))
                .thenReturn(emptyWorkspace());

        mockMvc.perform(get("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", "access-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value(TEAM_ID.toString()));
    }

    @DisplayName("구성원 추가 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsMemberCreationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.createMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(IDEMPOTENCY_KEY),
                eq("access-key"),
                any(CreateMemberCommand.class)
        )).thenReturn(new WorkspaceUseCase.MemberResult(MEMBER_ID, "최유진", "최", "#C8D6E5"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"최유진\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()));
    }

    @DisplayName("시즌 회차 생성 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsSeasonRoundCreationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.createSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(IDEMPOTENCY_KEY),
                eq("access-key"),
                any(CreateSeasonRoundCommand.class)
        )).thenReturn(new WorkspaceUseCase.SeasonRoundResult(
                ROUND_ID,
                "3회차",
                LocalDate.of(2026, 7, 27),
                List.of(),
                null
        ));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/rounds", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "3회차",
                                  "meetingDate": "2026-07-27"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROUND_ID.toString()));
    }

    @DisplayName("역할 자료 생성 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsRoleResourceCreationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.createRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(IDEMPOTENCY_KEY),
                eq("access-key"),
                any(CreateRoleResourceCommand.class)
        )).thenReturn(new WorkspaceUseCase.RoleResourceResult(
                RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                null
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "44444444-4444-4444-4444-444444444444",
                                  "title": "질문 정리 가이드",
                                  "url": "https://docs.example.com/question-guide",
                                  "description": null
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RESOURCE_ID.toString()));
    }

    @DisplayName("회차 루틴 실행 변경 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsRoutineExecutionWriteWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateRoutineExecutionCompletion(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                EXECUTION_ID,
                "access-key",
                true
        )).thenReturn(new WorkspaceUseCase.RoutineExecutionResult(
                EXECUTION_ID,
                ROUND_ID,
                ROUTINE_ID,
                "모임 전 질문 모으기",
                RoutinePhase.BEFORE,
                "모임 하루 전",
                ROLE_ID,
                RoutineStatus.DONE,
                "공통 질문을 정리합니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                                + "/routine-executions/{executionId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID,
                        EXECUTION_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @DisplayName("명시하지 않은 경로는 fallback 인증과 세션 없이 거부한다")
    @Test
    void deniesUnknownPathsWithoutFallbackAuthenticationOrSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/private"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(requestId).isNotNull();
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @DisplayName("Basic 인증 헤더를 보내도 명시하지 않은 경로는 열리지 않는다")
    @Test
    void rejectsBasicCredentialsForUnknownPaths() throws Exception {
        mockMvc.perform(get("/api/v1/private")
                        .with(httpBasic("user", "password")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @DisplayName("오류 디스패치는 보안 거부에 가려지지 않고 MVC까지 전달한다")
    @Test
    void permitsErrorDispatch() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/private")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(UUID.fromString(
                result.getResponse().getHeader(RequestIdFilter.HEADER_NAME)
        )).isNotNull();
    }

    @DisplayName("기본 로그아웃 경로는 활성화하지 않는다")
    @Test
    void doesNotExposeDefaultLogoutEndpoint() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().isForbidden());
    }

    private WorkspaceUseCase.WorkspaceResult emptyWorkspace() {
        return new WorkspaceUseCase.WorkspaceResult(
                new WorkspaceUseCase.TeamResult(TEAM_ID, "알고리즘 한 바퀴"),
                new WorkspaceUseCase.SeasonResult(
                        SEASON_ID,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
