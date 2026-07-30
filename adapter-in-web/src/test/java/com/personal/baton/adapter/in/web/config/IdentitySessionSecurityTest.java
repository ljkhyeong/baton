package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.identity.IdentityController;
import com.personal.baton.adapter.in.web.identity.IdentitySessionController;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        IdentitySessionController.class,
        IdentityController.class
})
@Import({SecurityConfig.class, WebFilterConfig.class})
class IdentitySessionSecurityTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-4333-8444-555555555555");
    private static final UUID MEMBER_ID =
            UUID.fromString("33333333-2222-4333-8444-555555555555");
    private static final UUID INVITATION_ID =
            UUID.fromString("44444444-2222-4333-8444-555555555555");
    private static final String IDEMPOTENCY_KEY =
            "55555555-2222-4333-8444-555555555555";
    private static final String INVITATION_TOKEN = "A".repeat(43);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private OwnerBootstrapInvitationUseCase invitationUseCase;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T12:00:00Z"));
    }

    @DisplayName("로그인하지 않은 세션 조회는 익명 상태를 반환하고 세션을 만들지 않는다")
    @Test
    void returnsAnonymousSessionWithoutCreatingSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.csrfToken").doesNotExist())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @DisplayName("로그인한 세션 조회는 계정 식별자와 세션 결속 CSRF 토큰을 반환한다")
    @Test
    void returnsAccountAndCsrfTokenForAuthenticatedSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/session")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @DisplayName("내 계정 조회는 로그인하지 않으면 JSON 401을 반환한다")
    @Test
    void requiresAuthenticationForMe() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다"));
    }

    @DisplayName("내 계정 조회는 세션 principal의 내부 계정 식별자만 반환한다")
    @Test
    void returnsOnlyInternalAccountIdForMe() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
    }

    @DisplayName("bootstrap 초대 발급은 세션과 CSRF 없이 application 운영자 키 검증으로 진입한다")
    @Test
    void permitsBootstrapIssuanceWithoutSessionOrCsrf() throws Exception {
        when(invitationUseCase.issue(
                "operator-key",
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenReturn(issuedInvitation(false));

        MvcResult result = mockMvc.perform(
                        post("/api/v1/identity/bootstrap-invitations")
                                .header(
                                        "X-Baton-Identity-Bootstrap-Key",
                                        "operator-key"
                                )
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "teamId": "%s",
                                          "memberId": "%s"
                                        }
                                        """.formatted(TEAM_ID, MEMBER_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$.token").value(INVITATION_TOKEN))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @DisplayName("bootstrap 초대 발급 재생은 같은 응답을 200으로 반환한다")
    @Test
    void returnsOkForBootstrapIssuanceReplay() throws Exception {
        when(invitationUseCase.issue(
                "operator-key",
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenReturn(issuedInvitation(true));

        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header("X-Baton-Identity-Bootstrap-Key", "operator-key")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "%s",
                                  "memberId": "%s"
                                }
                                """.formatted(TEAM_ID, MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()));
    }

    @DisplayName("초대 수락은 인증됐어도 CSRF 토큰이 없으면 application에 진입하지 않는다")
    @Test
    void rejectsInvitationAcceptanceWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + INVITATION_TOKEN + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        verifyNoInteractions(invitationUseCase);
    }

    @DisplayName("초대 수락은 세션 계정과 JSON 본문의 토큰만 application에 전달한다")
    @Test
    void acceptsInvitationWithAuthenticatedSessionAndCsrf() throws Exception {
        when(invitationUseCase.accept(
                INVITATION_TOKEN,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).thenReturn(new AcceptedOwnerBootstrapInvitation(
                INVITATION_ID,
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                Instant.parse("2026-07-30T12:00:00Z"),
                MemberIdentityRole.OWNER
        ));

        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + INVITATION_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"));

        verify(invitationUseCase).accept(
                INVITATION_TOKEN,
                new AuthenticatedAccount(ACCOUNT_ID)
        );
    }

    @DisplayName("로그아웃은 CSRF 토큰이 없으면 JSON 403으로 거부한다")
    @Test
    void rejectsLogoutWithoutCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/session/logout")
                        .session(session)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(session.isInvalid()).isFalse();
    }

    @DisplayName("로그아웃은 인증 세션을 무효화하고 본문 없이 성공한다")
    @Test
    void invalidatesAuthenticatedSessionOnLogout() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/session/logout")
                        .session(session)
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        assertThat(session.isInvalid()).isTrue();
    }

    @DisplayName("OIDC 모드가 꺼져 있으면 인증 시작 경로도 열리지 않는다")
    @Test
    void deniesOidcAuthorizationPathWhenDisabled() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/auth/oidc/authorization/google"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    private Authentication accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new BatonAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private IssuedOwnerBootstrapInvitation issuedInvitation(boolean replayed) {
        return new IssuedOwnerBootstrapInvitation(
                INVITATION_ID,
                TEAM_ID,
                MEMBER_ID,
                INVITATION_TOKEN,
                Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T13:00:00Z"),
                replayed
        );
    }
}
