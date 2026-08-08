package com.personal.baton.adapter.in.web.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.port.in.ReadParticipationGrantJwkSetUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ParticipationGrantResult;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ParticipationGrantControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:34:56.987654Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String ROOM_ID = "bcdf-ghjk-mnpq";
    private static final String PATH =
            "/round/rooms/" + ROOM_ID + "/participation-grant/refresh";

    private RoundAuthorizationUseCase roundAuthorizationUseCase;
    private ReadParticipationGrantJwkSetUseCase readJwkSetUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roundAuthorizationUseCase = mock(RoundAuthorizationUseCase.class);
        readJwkSetUseCase = mock(ReadParticipationGrantJwkSetUseCase.class);
        var controller = new ParticipationGrantController(
                roundAuthorizationUseCase,
                readJwkSetUseCase,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RoundAuthorizationExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("참여권 성공 응답은 JWT를 body에 노출하지 않고 방 전용 HttpOnly cookie만 회전한다")
    void writesRoomScopedGrantCookieOnly() throws Exception {
        long expiresAt = Instant.parse("2026-08-08T12:39:56Z").getEpochSecond();
        when(roundAuthorizationUseCase.issueParticipationGrant(any()))
                .thenReturn(new ParticipationGrantResult(
                        "header.payload.signature",
                        expiresAt,
                        240,
                        ROOM_ID
                ));

        var result = mockMvc.perform(post(PATH)
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId":"11111111-1111-4111-8111-111111111111",
                                  "seasonId":"22222222-2222-4222-8222-222222222222",
                                  "resourceId":"33333333-3333-4333-8333-333333333333"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(240))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("__Secure-round_access=header.payload.signature")
                .contains("Path=/round/rooms/" + ROOM_ID)
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Max-Age=299")
                .doesNotContain("Domain=");
    }

    @Test
    @DisplayName("hint가 없으면 Content-Type과 body를 모두 생략해야 한다")
    void acceptsMissingHintWithoutContentType() throws Exception {
        when(roundAuthorizationUseCase.issueParticipationGrant(any()))
                .thenReturn(new ParticipationGrantResult(
                        "header.payload.signature",
                        NOW.plusSeconds(300).getEpochSecond(),
                        240,
                        ROOM_ID
                ));

        mockMvc.perform(post(PATH).principal(authentication()))
                .andExpect(status().isOk());

        verify(roundAuthorizationUseCase).issueParticipationGrant(any());
    }

    @Test
    @DisplayName("추가 hint 필드는 INVALID_INPUT이며 기존 참여권 cookie를 유지한다")
    void rejectsUnknownHintFieldWithoutClearingCookie() throws Exception {
        mockMvc.perform(post(PATH)
                        .principal(authentication())
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

        verify(roundAuthorizationUseCase, never()).issueParticipationGrant(any());
    }

    @Test
    @DisplayName("멤버십이 없으면 403과 같은 room path의 만료 cookie를 반환한다")
    void clearsCookieWhenParticipationIsDenied() throws Exception {
        when(roundAuthorizationUseCase.issueParticipationGrant(any()))
                .thenThrow(new RoundParticipationDeniedException());

        var result = mockMvc.perform(post(PATH).principal(authentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROUND_PARTICIPATION_DENIED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("__Secure-round_access=")
                .contains("Max-Age=0")
                .contains("Path=/round/rooms/" + ROOM_ID);
    }

    @Test
    @DisplayName("공개 JWK Set은 public cache와 전용 media type으로 반환한다")
    void exposesPublicJwkSet() throws Exception {
        when(readJwkSetUseCase.readPublicJwkSetJson())
                .thenReturn("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"current\"}]}");

        mockMvc.perform(get(ParticipationGrantController.JWK_SET_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/jwk-set+json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, public"))
                .andExpect(content().json(
                        "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"current\"}]}",
                        true
                ));
    }

    private UsernamePasswordAuthenticationToken authentication() {
        AuthenticatedAccountPrincipal principal = () -> ACCOUNT_ID;
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of());
    }
}
