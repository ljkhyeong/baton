package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.link.RoleResourceLinkController;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.OpenRoleResourceLinkResult;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.RoutingMode;
import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRoleController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceSeasonController;
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        WorkspaceController.class,
        WorkspaceRoleController.class,
        WorkspaceSeasonController.class,
        RoleResourceLinkController.class
})
@Import({SecurityConfig.class, WebFilterConfig.class})
class WorkspaceSecurityTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEXT_SEASON_ID =
            UUID.fromString("22222222-2222-2222-2222-333333333333");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROUTINE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROUND_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID EXECUTION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-security-0001";
    private static final String LINK_IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceUseCase workspaceUseCase;

    @MockitoBean
    private RoleResourceLinkUseCase roleResourceLinkUseCase;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T12:00:00Z"));
    }

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

    @DisplayName("세션 워크스페이스 생성은 로그인하지 않으면 레거시 생성으로 내려가지 않고 401을 반환한다")
    @Test
    void requiresAuthenticationForOwnedWorkspaceCreationWithoutLegacyFallback() throws Exception {
        mockMvc.perform(post("/api/v1/me/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서"],
                                  "ownerMemberName": "박민서"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verify(workspaceUseCase, never()).createWorkspaceForOwner(
                any(),
                any(),
                any(),
                any()
        );
        verify(workspaceUseCase, never()).createWorkspace(any(), any(), any());
    }

    @DisplayName("세션 워크스페이스 생성은 인증됐어도 CSRF 토큰이 없으면 application에 진입하지 않는다")
    @Test
    void requiresCsrfForOwnedWorkspaceCreation() throws Exception {
        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서"],
                                  "ownerMemberName": "박민서"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        verify(workspaceUseCase, never()).createWorkspaceForOwner(
                any(),
                any(),
                any(),
                any()
        );
    }

    @DisplayName("로그인 사용자는 동적 CSRF와 선택한 OWNER로 접근 키 없는 워크스페이스를 만든다")
    @Test
    void createsOwnedWorkspaceWithAuthenticatedSessionAndCsrf() throws Exception {
        AuthenticatedAccount account = new AuthenticatedAccount(ACCOUNT_ID);
        when(workspaceUseCase.createWorkspaceForOwner(
                eq(IDEMPOTENCY_KEY),
                eq(account),
                eq("박민서"),
                any(CreateWorkspaceCommand.class)
        )).thenReturn(new WorkspaceUseCase.CreatedWorkspaceResult(TEAM_ID, SEASON_ID, null));

        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서", "김준호"],
                                  "ownerMemberName": "박민서"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "/api/v1/teams/" + TEAM_ID + "/seasons/" + SEASON_ID + "/workspace"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.seasonId").value(SEASON_ID.toString()))
                .andExpect(jsonPath("$.accessKey").doesNotExist());
    }

    @DisplayName("세션 워크스페이스 생성의 입력 오류는 민감한 응답을 캐시하지 않는다")
    @Test
    void preventsCachingOwnedWorkspaceValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
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
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(workspaceUseCase, never()).createWorkspaceForOwner(
                any(),
                any(),
                any(),
                any()
        );
    }

    @DisplayName("세션 워크스페이스 생성의 계정·멱등·서버 오류는 모두 민감한 응답을 캐시하지 않는다")
    @Test
    void preventsCachingOwnedWorkspaceApplicationErrors() throws Exception {
        when(workspaceUseCase.createWorkspaceForOwner(
                eq(IDEMPOTENCY_KEY),
                eq(new AuthenticatedAccount(ACCOUNT_ID)),
                eq("박민서"),
                any(CreateWorkspaceCommand.class)
        )).thenThrow(new IdentityNotFoundException(
                "ACCOUNT_NOT_FOUND",
                "사용자 계정을 찾을 수 없습니다"
        )).thenThrow(new IdempotencyKeyReusedException())
                .thenThrow(new IllegalStateException("sensitive internal detail"));
        String requestBody = """
                {
                  "teamName": "알고리즘 한 바퀴",
                  "seasonName": "2026 여름 시즌",
                  "startDate": "2026-07-02",
                  "endDate": "2026-09-17",
                  "memberNames": ["박민서"],
                  "ownerMemberName": "박민서"
                }
                """;

        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/v1/me/workspaces")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버에서 요청을 처리하지 못했습니다"));
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

    @DisplayName("로그인 세션은 공유 접근 키 없이 활성 구성원 권한으로 워크스페이스를 조회한다")
    @Test
    void permitsWorkspaceReadWithAuthenticatedSession() throws Exception {
        SessionAccount authorization = sessionAuthorization();
        when(workspaceUseCase.getWorkspaceAuthorized(TEAM_ID, SEASON_ID, authorization))
                .thenReturn(emptyWorkspace());

        mockMvc.perform(get(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/workspace",
                        TEAM_ID,
                        SEASON_ID)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value(TEAM_ID.toString()));
    }

    @DisplayName("로그인 세션의 워크스페이스 변경은 공유 접근 키 헤더가 있어도 CSRF 토큰 없이는 거부한다")
    @Test
    void requiresCsrfForAuthenticatedWorkspaceMutationEvenWithLegacyHeader() throws Exception {
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}",
                        TEAM_ID,
                        SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        verify(workspaceUseCase, never()).updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq("access-key"),
                any(UpdateSeasonCommand.class)
        );
    }

    @DisplayName("로그인 세션은 CSRF 토큰과 활성 구성원 권한으로 워크스페이스를 변경한다")
    @Test
    void permitsAuthenticatedWorkspaceMutationWithCsrf() throws Exception {
        SessionAccount authorization = sessionAuthorization();
        when(workspaceUseCase.updateSeasonAuthorized(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(authorization),
                any(UpdateSeasonCommand.class)
        )).thenReturn(seasonResult(SEASON_ID, null, null));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}",
                        TEAM_ID,
                        SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SEASON_ID.toString()));
    }

    @DisplayName("명시한 잘못된 공유 접근 키는 로그인 세션 권한으로 대체하지 않는다")
    @Test
    void doesNotFallbackToSessionWhenExplicitLegacyKeyIsWrong() throws Exception {
        when(workspaceUseCase.getWorkspace(TEAM_ID, SEASON_ID, "wrong-access-key"))
                .thenThrow(new WorkspaceAccessDeniedException());

        mockMvc.perform(get(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/workspace",
                        TEAM_ID,
                        SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", "wrong-access-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        verify(workspaceUseCase, never()).getWorkspaceAuthorized(
                eq(TEAM_ID),
                eq(SEASON_ID),
                any()
        );
    }

    @DisplayName("시즌 수정 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsSeasonUpdateWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq("access-key"),
                any(UpdateSeasonCommand.class)
        )).thenReturn(seasonResult(SEASON_ID, null, null));

        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SEASON_ID.toString()));
    }

    @DisplayName("자동 회차 일정 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsRoundScheduleUpdateWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateRoundSchedule(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq("access-key"),
                any(UpdateRoundScheduleCommand.class)
        )).thenReturn(seasonResult(SEASON_ID, null, null));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timeZone": "Asia/Seoul",
                                  "firstMeetingDate": "2026-08-06",
                                  "meetingTime": "20:30",
                                  "recurrence": "%s",
                                  "generationLeadDays": 7,
                                  "enabled": true
                                }
                                """.formatted(RoundRecurrence.WEEKLY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SEASON_ID.toString()));
    }

    @DisplayName("시즌 종료 상태 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsSeasonEndingWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateSeasonEnding(TEAM_ID, SEASON_ID, "access-key", true))
                .thenReturn(seasonResult(
                        SEASON_ID,
                        Instant.parse("2026-09-18T00:00:00Z"),
                        null
                ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/ending",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").value("2026-09-18T00:00:00Z"));
    }

    @DisplayName("다음 시즌 생성 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsNextSeasonCreationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(IDEMPOTENCY_KEY),
                eq("access-key"),
                any(CreateNextSeasonCommand.class)
        )).thenReturn(new WorkspaceUseCase.NextSeasonResult(
                seasonResult(SEASON_ID, Instant.parse("2026-09-18T00:00:00Z"), null),
                seasonResult(NEXT_SEASON_ID, null, SEASON_ID),
                List.of(),
                List.of()
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/successor",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "2026 가을 시즌",
                                  "startDate": "2026-09-18",
                                  "endDate": "2026-12-17",
                                  "copyRoleIds": [],
                                  "copyRoutineIds": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/teams/" + TEAM_ID
                                + "/seasons/" + NEXT_SEASON_ID + "/workspace"
                ));
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
        )).thenReturn(new WorkspaceUseCase.MemberResult(
                MEMBER_ID,
                "최유진",
                "최",
                "#C8D6E5",
                null
        ));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"최유진\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()));
    }

    @DisplayName("구성원 수정 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsMemberUpdateWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(MEMBER_ID),
                eq("access-key"),
                any(UpdateMemberCommand.class)
        )).thenReturn(new WorkspaceUseCase.MemberResult(
                MEMBER_ID,
                "최유진(리드)",
                "최",
                "#C8D6E5",
                null
        ));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"최유진(리드)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("최유진(리드)"));
    }

    @DisplayName("구성원 활동 상태 변경 경로는 CSRF 토큰 없이 application 접근 키 검증으로 진입한다")
    @Test
    void permitsMemberDeactivationWithoutAuthenticationOrCsrf() throws Exception {
        when(workspaceUseCase.updateMemberDeactivation(
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                "access-key",
                true
        )).thenReturn(new WorkspaceUseCase.MemberResult(
                MEMBER_ID,
                "최유진",
                "최",
                "#C8D6E5",
                Instant.parse("2026-07-29T03:04:05Z")
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deactivated\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivatedAt").value("2026-07-29T03:04:05Z"));
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
                null,
                Instant.parse("2026-07-20T03:04:05Z")
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

    @DisplayName("역할 자료 열기 경로는 사용자 인증 세션과 CSRF 토큰 없이 application 접근 검증으로 진입한다")
    @Test
    void permitsRoleResourceLinkOpeningWithoutAuthenticationOrCsrf() throws Exception {
        Instant expiresAt = Instant.parse("2026-07-30T12:10:00Z");
        when(roleResourceLinkUseCase.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                "access-key",
                LINK_IDEMPOTENCY_KEY,
                expiresAt
        )).thenReturn(new OpenRoleResourceLinkResult(
                URI.create("https://go.example/l/opaque-code"),
                RoutingMode.BATON_GO,
                expiresAt
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID)
                        .header("Idempotency-Key", LINK_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "access-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-07-30T12:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routingMode").value("BATON_GO"));
    }

    @DisplayName("로그인 세션은 CSRF 토큰과 구성원 권한으로 역할 자료 열기 링크를 발급한다")
    @Test
    void permitsRoleResourceLinkOpeningWithAuthenticatedSessionAndCsrf() throws Exception {
        Instant expiresAt = Instant.parse("2026-07-30T12:10:00Z");
        SessionAccount authorization = sessionAuthorization();
        when(roleResourceLinkUseCase.openRoleResourceLinkAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization,
                LINK_IDEMPOTENCY_KEY,
                expiresAt
        )).thenReturn(new OpenRoleResourceLinkResult(
                URI.create("https://go.example/l/opaque-code"),
                RoutingMode.BATON_GO,
                expiresAt
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}"
                                + "/role-resources/{resourceId}/open-link",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID)
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("Idempotency-Key", LINK_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-07-30T12:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routingMode").value("BATON_GO"));
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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private WorkspaceUseCase.WorkspaceResult emptyWorkspace() {
        return new WorkspaceUseCase.WorkspaceResult(
                new WorkspaceUseCase.TeamResult(TEAM_ID, "알고리즘 한 바퀴"),
                seasonResult(SEASON_ID, null, null),
                List.of(new WorkspaceUseCase.SeasonSummaryResult(
                        SEASON_ID,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17),
                        null,
                        null
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private SessionAccount sessionAuthorization() {
        return new SessionAccount(new AuthenticatedAccount(ACCOUNT_ID));
    }

    private Authentication accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new BatonAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private WorkspaceUseCase.SeasonResult seasonResult(
            UUID seasonId,
            Instant endedAt,
            UUID previousSeasonId
    ) {
        return new WorkspaceUseCase.SeasonResult(
                seasonId,
                seasonId.equals(SEASON_ID) ? "2026 여름 시즌" : "2026 가을 시즌",
                seasonId.equals(SEASON_ID)
                        ? LocalDate.of(2026, 7, 2)
                        : LocalDate.of(2026, 9, 18),
                seasonId.equals(SEASON_ID)
                        ? LocalDate.of(2026, 9, 17)
                        : LocalDate.of(2026, 12, 17),
                endedAt,
                previousSeasonId
        );
    }
}
