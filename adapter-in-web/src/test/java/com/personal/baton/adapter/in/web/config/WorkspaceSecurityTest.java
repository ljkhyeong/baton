package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceUseCase workspaceUseCase;

    @DisplayName("워크스페이스 생성 경로는 Basic 인증과 CSRF 토큰 없이 호출할 수 있다")
    @Test
    void permitsWorkspaceCreationWithoutBasicAuthOrCsrf() throws Exception {
        when(workspaceUseCase.createWorkspace(any(CreateWorkspaceCommand.class)))
                .thenReturn(new WorkspaceUseCase.CreatedWorkspaceResult(TEAM_ID, SEASON_ID, "access-key"));

        mockMvc.perform(post("/api/v1/workspaces")
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

    @DisplayName("워크스페이스 변경 경로는 Basic 인증과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsScopedWorkspaceWriteWithoutBasicAuthOrCsrf() throws Exception {
        when(workspaceUseCase.updateRoutineCompletion(TEAM_ID, SEASON_ID, ROUTINE_ID, "access-key", true))
                .thenReturn(new WorkspaceUseCase.RoutineResult(
                        ROUTINE_ID,
                        "모임 전 질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        ROLE_ID,
                        RoutineStatus.DONE,
                        "공통 질문을 정리합니다"
                ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion",
                        TEAM_ID, SEASON_ID, ROUTINE_ID)
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
                List.of()
        );
    }
}
