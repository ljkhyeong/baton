package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.round.RoundJwkSetController;
import com.personal.baton.adapter.in.web.round.RoundParticipationGrantController;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.PublicRoundJwkSet;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        RoundParticipationGrantController.class,
        RoundJwkSetController.class
})
@Import({SecurityConfig.class, WebFilterConfig.class})
class RoundParticipationGrantSecurityTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SEASON_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESOURCE_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String GRANT_PATH =
            "/api/v1/teams/" + TEAM_ID
                    + "/seasons/" + SEASON_ID
                    + "/role-resources/" + RESOURCE_ID
                    + "/round-participation-grant";
    private static final String ORIGIN = "http://localhost:8080";
    private static final String ROOM_ID = "abcd-efgh-jkmn";
    private static final String ROOM_GRANT_PATH =
            "/api/v1/round/rooms/" + ROOM_ID + "/participation-grant";
    private static final String ROOM_REFRESH_PATH =
            "/round/rooms/" + ROOM_ID + "/participation-grant/refresh";
    private static final Instant ISSUED_AT = Instant.parse("2026-07-31T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private RoundParticipationGrantUseCase useCase;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(ISSUED_AT);
    }

    @DisplayName("참여권 발급은 로그인 세션이 없으면 공유 접근 키가 있어도 거절한다")
    @Test
    void rejectsSharedAccessKeyWithoutAuthenticatedSession() throws Exception {
        mockMvc.perform(post(GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("X-Baton-Access-Key", "shared-workspace-key")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("참여권 발급은 로그인했어도 CSRF 토큰이 없으면 거절한다")
    @Test
    void rejectsAuthenticatedRequestWithoutCsrf() throws Exception {
        mockMvc.perform(post(GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("참여권 발급은 Origin이 없거나 Fetch Metadata가 cross-site이면 거절한다")
    @Test
    void rejectsMissingOrCrossSiteOriginEvidence() throws Exception {
        mockMvc.perform(post(GRANT_PATH)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ROUND_GRANT_ORIGIN_INVALID"));

        mockMvc.perform(post(GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "cross-site")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROUND_GRANT_ORIGIN_INVALID"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("참여권 발급 성공은 본문 없이 room 범위의 host-only 보안 쿠키만 설정한다")
    @Test
    void setsRoomScopedSecureCookieWithoutResponseBody() throws Exception {
        when(useCase.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()))
                .thenReturn(grant());

        MvcResult result = mockMvc.perform(post(GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("__Secure-round_access=header.payload.signature;")
                .contains("Path=/round/rooms/abcd-efgh-jkmn")
                .contains("Max-Age=300")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=");
        verify(useCase).issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account());
    }

    @DisplayName("서명기 장애는 쿠키 없이 안정적인 503 오류를 반환한다")
    @Test
    void returnsServiceUnavailableWithoutCookieWhenSignerFails() throws Exception {
        when(useCase.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_SIGNER_UNAVAILABLE",
                        "ROUND 참여권 서명기를 사용할 수 없습니다"
                ));

        mockMvc.perform(post(GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value(
                        "ROUND_GRANT_SIGNER_UNAVAILABLE"
                ));
    }

    @DisplayName("복사한 room 참여권도 같은 세션·CSRF·동일 출처 계약으로 보안 쿠키를 발급한다")
    @Test
    void setsRoomScopedCookieForFallbackGrant() throws Exception {
        when(useCase.issueForRoom(ROOM_ID, account())).thenReturn(grant());

        mockMvc.perform(post(ROOM_GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString(
                                "Path=/round/rooms/" + ROOM_ID
                        )
                ))
                .andExpect(content().string(""));

        verify(useCase).issueForRoom(ROOM_ID, account());
    }

    @DisplayName("복사한 room 참여권도 로그인 세션 없이 공유 접근 키만 보내면 거절한다")
    @Test
    void rejectsSharedAccessKeyForFallbackGrant() throws Exception {
        mockMvc.perform(post(ROOM_GRANT_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("X-Baton-Access-Key", "shared-workspace-key")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("복사한 room의 권한 없음과 모호한 역할 자료는 쿠키 없이 403과 409로 구분한다")
    @Test
    void rejectsForbiddenAndAmbiguousFallbackGrant() throws Exception {
        when(useCase.issueForRoom(ROOM_ID, account()))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_FORBIDDEN",
                        "현재 계정으로 이 ROUND room에 참여할 수 없습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_RESOURCE_AMBIGUOUS",
                        "ROUND room에 연결된 역할 자료를 하나로 결정할 수 없습니다"
                ));

        performFallbackGrant()
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value("ROUND_GRANT_FORBIDDEN"));

        performFallbackGrant()
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value("ROUND_RESOURCE_AMBIGUOUS"));
    }

    @DisplayName("ROUND 참여권 갱신은 로그인 세션과 CSRF 토큰이 모두 필요하다")
    @Test
    void requiresAuthenticatedSessionAndCsrfForRefresh() throws Exception {
        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("ROUND 참여권 갱신은 정확한 동일 출처 증거가 없으면 거절한다")
    @Test
    void rejectsRefreshWithoutExactSameOriginEvidence() throws Exception {
        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "cross-site")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ROUND_GRANT_ORIGIN_INVALID"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("ROUND 참여권 갱신은 쿠키를 회전하고 숫자 만료 메타데이터만 반환한다")
    @Test
    void refreshesGrantWithExactLeaseMetadata() throws Exception {
        when(useCase.issueForRoom(ROOM_ID, account())).thenReturn(grant());

        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString(
                                "Path=/round/rooms/" + ROOM_ID
                        )
                ))
                .andExpect(jsonPath("$.expiresAt").value(
                        ISSUED_AT.plusSeconds(300).getEpochSecond()
                ))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(240))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(useCase).issueForRoom(ROOM_ID, account());
    }

    @DisplayName("ROUND 입장 locator가 있으면 지정 팀·회차·자료를 갱신 권한 재검증에 전달한다")
    @Test
    void refreshesGrantUsingEntryLocator() throws Exception {
        when(useCase.issueForRoom(
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                account()
        )).thenReturn(grant());

        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "%s",
                                  "seasonId": "%s",
                                  "resourceId": "%s"
                                }
                                """.formatted(TEAM_ID, SEASON_ID, RESOURCE_ID))
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshAfterSeconds").value(240));

        verify(useCase).issueForRoom(
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                account()
        );
    }

    @DisplayName("불완전한 ROUND 입장 locator는 권한 조회 전에 400으로 거절한다")
    @Test
    void rejectsIncompleteEntryLocator() throws Exception {
        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "%s",
                                  "seasonId": "%s"
                                }
                                """.formatted(TEAM_ID, SEASON_ID))
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("알 수 없는 필드가 섞인 ROUND 입장 locator는 권한 조회 전에 거절한다")
    @Test
    void rejectsEntryLocatorWithUnknownField() throws Exception {
        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "%s",
                                  "seasonId": "%s",
                                  "resourceId": "%s",
                                  "accessKey": "must-not-be-accepted"
                                }
                                """.formatted(TEAM_ID, SEASON_ID, RESOURCE_ID))
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("ROUND 참여권 갱신의 공유 잠금 충돌도 저장할 수 없는 409로 반환한다")
    @Test
    void returnsNoStoreConflictWhenRefreshLockTimesOut() throws Exception {
        when(useCase.issueForRoom(ROOM_ID, account()))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(post(ROOM_REFRESH_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .with(authentication(accountAuthentication()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"));
    }

    @DisplayName("JWKS는 로그인 없이 공개키와 60초 재검증 cache 계약을 반환한다")
    @Test
    void returnsPublicJwkSetAnonymously() throws Exception {
        when(useCase.getPublicJwkSet()).thenReturn(jwkSet());

        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"sha256-public-key-set\""))
                .andExpect(jsonPath("$.keys[0].kid").value("round-2026-07"))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("public", "max-age=60", "must-revalidate");
    }

    @DisplayName("JWKS ETag가 같으면 공개키 본문 없이 304를 반환한다")
    @Test
    void returnsNotModifiedForMatchingJwkSetEtag() throws Exception {
        when(useCase.getPublicJwkSet()).thenReturn(jwkSet());

        mockMvc.perform(get("/.well-known/jwks.json")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"sha256-public-key-set\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"sha256-public-key-set\""
                ))
                .andExpect(content().string(""));
    }

    @DisplayName("JWKS 조건부 요청은 복수 weak ETag도 HTTP 표준 규칙으로 비교한다")
    @Test
    void returnsNotModifiedForWeakMatchingJwkSetEtagAmongCandidates() throws Exception {
        when(useCase.getPublicJwkSet()).thenReturn(jwkSet());

        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json")
                        .header(
                                HttpHeaders.IF_NONE_MATCH,
                                "\"stale-key-set\", W/\"sha256-public-key-set\""
                        ))
                .andExpect(status().isNotModified())
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"sha256-public-key-set\""
                ))
                .andExpect(content().string(""))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("public", "max-age=60", "must-revalidate");
    }

    private IssuedRoundParticipationGrant grant() {
        return new IssuedRoundParticipationGrant(
                "header.payload.signature",
                "abcd-efgh-jkmn",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(300),
                300,
                240
        );
    }

    private PublicRoundJwkSet jwkSet() {
        return new PublicRoundJwkSet(
                """
                        {
                          "keys": [
                            {
                              "kid": "round-2026-07",
                              "kty": "RSA",
                              "alg": "RS256",
                              "use": "sig",
                              "n": "public-modulus",
                              "e": "AQAB"
                            }
                          ]
                        }
                        """,
                "sha256-public-key-set"
        );
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(ACCOUNT_ID);
    }

    private Authentication accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new BatonAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private org.springframework.test.web.servlet.ResultActions performFallbackGrant()
            throws Exception {
        return mockMvc.perform(post(ROOM_GRANT_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Sec-Fetch-Site", "same-origin")
                .with(authentication(accountAuthentication()))
                .with(csrf()));
    }
}
