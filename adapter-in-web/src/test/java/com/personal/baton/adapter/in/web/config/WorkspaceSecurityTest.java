package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkspaceController.class)
@Import(SecurityConfig.class)
class WorkspaceSecurityTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
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

    @DisplayName("워크스페이스 생성 경로는 Basic 인증과 CSRF 토큰 없이 호출할 수 있다")
    @Test
    void permitsWorkspaceCreationWithoutBasicAuthOrCsrf() throws Exception {
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

    @DisplayName("접근 키 복구 경로는 Basic 인증과 CSRF 토큰 없이 application 운영자 키 검증으로 진입한다")
    @Test
    void permitsAccessKeyRecoveryWithoutBasicAuthOrCsrf() throws Exception {
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

    @DisplayName("워크스페이스 조회 경로는 Basic 인증 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsScopedWorkspaceReadWithoutBasicAuth() throws Exception {
        when(workspaceUseCase.getWorkspace(TEAM_ID, SEASON_ID, "access-key"))
                .thenReturn(emptyWorkspace());

        mockMvc.perform(get("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", "access-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value(TEAM_ID.toString()));
    }

    @DisplayName("시즌 회차 생성 경로는 Basic 인증과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsSeasonRoundCreationWithoutBasicAuthOrCsrf() throws Exception {
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

    @DisplayName("역할 자료 생성 경로는 Basic 인증과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsRoleResourceCreationWithoutBasicAuthOrCsrf() throws Exception {
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

    @DisplayName("회차 루틴 실행 변경 경로는 Basic 인증과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsRoutineExecutionWriteWithoutBasicAuthOrCsrf() throws Exception {
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

    @DisplayName("워크스페이스 외 비공개 경로는 계속 Basic 인증을 요구한다")
    @Test
    void keepsBasicAuthenticationForOtherPaths() throws Exception {
        mockMvc.perform(get("/api/v1/private"))
                .andExpect(status().isUnauthorized());
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
