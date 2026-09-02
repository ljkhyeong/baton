package com.personal.baton.adapter.in.web.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.auth.AccountSessionPrincipal;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.ParticipationGrantResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

class ParticipationGrantControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:34:56.987654Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String ROOM_ID = "bcdf-ghjk-mnpq";
    private static final String PATH =
            "/round/rooms/" + ROOM_ID + "/participation-grant/refresh";

    private RoundParticipationUseCase roundParticipationUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roundParticipationUseCase = mock(RoundParticipationUseCase.class);
        var controller = new ParticipationGrantController(
                roundParticipationUseCase,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RoundAuthorizationExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(
                                new HttpSessionSecurityContextRepository()
                        )
                ))))
                .build();
    }

    @Test
    @DisplayName("참여권 cookie는 JWT보다 오래 남지 않도록 부분 초를 버린다")
    void roundsDownCookieLifetimeBelowGrantExpiry() {
        var cookie = RoundGrantCookie.issue(
                ROOM_ID,
                "header.payload.signature",
                NOW.plusSeconds(300).getEpochSecond(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(299);
    }

    @Test
    @DisplayName("hint가 없으면 Content-Type과 body를 모두 생략해야 한다")
    void acceptsMissingHintWithoutContentType() throws Exception {
        when(roundParticipationUseCase.issueParticipationGrant(any()))
                .thenReturn(new ParticipationGrantResult(
                        "header.payload.signature",
                        NOW.plusSeconds(300).getEpochSecond(),
                        240,
                        ROOM_ID
                ));

        mockMvc.perform(post(PATH).with(authentication(accountAuthentication())))
                .andExpect(status().isOk());

        verify(roundParticipationUseCase).issueParticipationGrant(any());
    }

    @Test
    @DisplayName("추가 hint 필드는 INVALID_INPUT이며 기존 참여권 cookie를 유지한다")
    void rejectsUnknownHintFieldWithoutClearingCookie() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId":"11111111-1111-4111-8111-111111111111",
                                  "seasonId":"22222222-2222-4222-8222-222222222222",
                                  "resourceId":"33333333-3333-4333-8333-333333333333",
                                  "role":"host"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(roundParticipationUseCase, never()).issueParticipationGrant(any());
    }

    @Test
    @DisplayName("멤버십이 없으면 403과 같은 room path의 만료 cookie를 반환한다")
    void clearsCookieWhenParticipationIsDenied() throws Exception {
        when(roundParticipationUseCase.issueParticipationGrant(any()))
                .thenThrow(new RoundParticipationDeniedException());

        var result = mockMvc.perform(post(PATH).with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROUND_PARTICIPATION_DENIED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("__Secure-round_access=")
                .contains("Max-Age=0")
                .contains("Path=/round/rooms/" + ROOM_ID);
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        AuthenticatedAccountPrincipal principal = new AccountSessionPrincipal(ACCOUNT_ID, 0);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of());
    }
}
