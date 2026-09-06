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
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.IssueParticipationGrantCommand;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.ParticipationGrantResult;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.RoundRoomHint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("소문자 UUID 세 필드를 검증하고 참여권 발급에 전달한다")
    void bindsValidatedRoomHint() throws Exception {
        var hint = new RoundRoomHint(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(roundParticipationUseCase.issueParticipationGrant(any()))
                .thenReturn(new ParticipationGrantResult(
                        "header.payload.signature", NOW.plusSeconds(300).getEpochSecond(), 240, ROOM_ID
                ));

        mockMvc.perform(post(PATH).with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","seasonId":"%s","resourceId":"%s"}
                                """.formatted(hint.teamId(), hint.seasonId(), hint.resourceId())))
                .andExpect(status().isOk());

        verify(roundParticipationUseCase).issueParticipationGrant(
                new IssueParticipationGrantCommand(ACCOUNT_ID, ROOM_ID, hint)
        );
    }

    @DisplayName("UUID가 없거나 숫자·축약형·대문자이면 쿠키를 유지하고 요청을 거부한다")
    @ParameterizedTest
    @ValueSource(strings = {"null", "123", "\"1-1-1-1-1\"", "\"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA\""})
    void rejectsNonCanonicalUuid(String teamId) throws Exception {
        mockMvc.perform(post(PATH).with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":%s,"seasonId":"22222222-2222-4222-8222-222222222222",
                                 "resourceId":"33333333-3333-4333-8333-333333333333"}
                                """.formatted(teamId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(roundParticipationUseCase, never()).issueParticipationGrant(any());
    }

    @DisplayName("JSON 요청의 빈 본문·null·필드 누락·배열은 참여권을 발급하지 않는다")
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[]"})
    void rejectsMissingOrNonObjectHint(String body) throws Exception {
        mockMvc.perform(post(PATH).with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(roundParticipationUseCase, never()).issueParticipationGrant(any());
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
