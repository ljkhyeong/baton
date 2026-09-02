package com.personal.baton.adapter.in.web.roundauth;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.anyLong;

import org.junit.jupiter.api.BeforeEach;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
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
        ParticipationGrantController.class,
        RoundAdministrationController.class
})
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        RoundGrantSecurityConfigTest.PasswordEncoderTestConfig.class
})
class RoundGrantSecurityConfigTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @BeforeEach
    void acceptCurrentAccountSessions() {
        when(validateAccountSessionUseCase.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
    }

    private static final String ROOM_ID = "abcd-efgh-jkmn";
    private static final String REFRESH_PATH =
            "/round/rooms/" + ROOM_ID + "/participation-grant/refresh";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoundAdministrationUseCase roundAdministrationUseCase;

    @MockitoBean
    private RoundParticipationUseCase roundParticipationUseCase;

    @MockitoBean
    private Clock clock;

    @DisplayName("ROUND public JWK Set은 계정 session 없이 조회할 수 있다")
    @Test
    void permitsPublicJwkSet() throws Exception {
        when(roundParticipationUseCase.readPublicJwkSetJson())
                .thenReturn("{\"keys\":[]}");

        mockMvc.perform(get(ParticipationGrantController.JWK_SET_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        "application/jwk-set+json"
                ));
    }

    @DisplayName("현재 계정 연결 조회는 actual chain에서 계정 session을 요구한다")
    @Test
    void rejectsAnonymousCurrentMembershipLookupThroughSecurityChain() throws Exception {
        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .queryParam("teamId", "11111111-1111-4111-8111-111111111111")
                        .header("X-Baton-Access-Key", "workspace-access-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "code": "AUTHENTICATION_REQUIRED",
                          "message": "BATON 계정 로그인이 필요합니다"
                        }
                        """, true));
    }

    @DisplayName("현재 계정 연결 GET은 actual chain에서 인증 뒤 CSRF 없이 조회할 수 있다")
    @Test
    void permitsAuthenticatedCurrentMembershipLookupWithoutCsrf() throws Exception {
        UUID accountId = UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
        UUID teamId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TestAccountPrincipal(accountId),
                        null,
                        List.of()
                );
        when(roundAdministrationUseCase.findCurrentMembership(
                new RoundAdministrationUseCase.CurrentMembershipQuery(
                        accountId,
                        teamId,
                        "workspace-access-key"
                )
        )).thenReturn(Optional.empty());

        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .with(authentication(authentication))
                        .queryParam("teamId", teamId.toString())
                        .header("X-Baton-Access-Key", "workspace-access-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "claimed": false
                        }
                        """, true));
    }

    @DisplayName("현재 ROUND 방 GET은 actual chain에서 인증 뒤 CSRF 없이 조회할 수 있다")
    @Test
    void permitsAuthenticatedCurrentRoomMappingLookupWithoutCsrf() throws Exception {
        UUID accountId = UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
        UUID teamId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID seasonId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TestAccountPrincipal(accountId),
                        null,
                        List.of()
                );
        when(roundAdministrationUseCase.findCurrentRoomMappings(
                new RoundAdministrationUseCase.CurrentRoomMappingsQuery(
                        accountId,
                        teamId,
                        seasonId,
                        "workspace-access-key"
                )
        )).thenReturn(List.of());

        mockMvc.perform(get(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                        .with(authentication(authentication))
                        .queryParam("teamId", teamId.toString())
                        .queryParam("seasonId", seasonId.toString())
                        .header("X-Baton-Access-Key", "workspace-access-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "mappings": []
                        }
                        """, true));
    }

    @DisplayName("현재 ROUND 방 GET은 actual chain에서 계정 session을 요구한다")
    @Test
    void rejectsAnonymousCurrentRoomMappingLookupThroughSecurityChain() throws Exception {
        mockMvc.perform(get(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                        .queryParam("teamId", "11111111-1111-4111-8111-111111111111")
                        .queryParam("seasonId", "22222222-2222-4222-8222-222222222222")
                        .header("X-Baton-Access-Key", "workspace-access-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "code": "AUTHENTICATION_REQUIRED",
                          "message": "BATON 계정 로그인이 필요합니다"
                        }
                        """, true));
    }

    @DisplayName("ROUND refresh의 CSRF 거부는 token cookie를 지우지 않고 stable 403을 반환한다")
    @Test
    void mapsRefreshCsrfFailureWithoutExpiringGrantCookie() throws Exception {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TestAccountPrincipal(
                                UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b")
                        ),
                        null,
                        List.of()
                );

        mockMvc.perform(post(REFRESH_PATH)
                        .with(authentication(authentication))
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Secure-round_access", "existing-token")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                ).noneMatch(value -> value.startsWith("__Secure-round_access=")))
                .andExpect(jsonPath("$.code").value("REQUEST_FORBIDDEN"));
    }

    @DisplayName("ROUND refresh는 CSRF보다 먼저 계정 session을 확인하고 미인증 cookie를 만료한다")
    @Test
    void rejectsAnonymousRefreshBeforeController() throws Exception {
        mockMvc.perform(post(REFRESH_PATH)
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Secure-round_access", "existing-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @DisplayName("ROUND 관리 경로의 미인증 요청은 actual chain에서 stable 401을 반환한다")
    @Test
    void rejectsAnonymousRoundAdministrationThroughSecurityChain() throws Exception {
        mockMvc.perform(sameOrigin(post(RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "code": "AUTHENTICATION_REQUIRED",
                          "message": "BATON 계정 로그인이 필요합니다"
                        }
                        """, true));
    }

    @DisplayName("ROUND 관리 경로의 CSRF 거부는 actual chain에서 stable 403을 반환한다")
    @Test
    void rejectsRoundAdministrationWithoutCsrfThroughSecurityChain() throws Exception {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TestAccountPrincipal(
                                UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b")
                        ),
                        null,
                        List.of()
                );

        mockMvc.perform(sameOrigin(post(RoundAdministrationController.ROOM_MAPPINGS_PATH))
                        .with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "code": "REQUEST_FORBIDDEN",
                          "message": "요청을 허용할 수 없습니다"
                        }
                        """, true));
    }

    private MockHttpServletRequestBuilder sameOrigin(MockHttpServletRequestBuilder request) {
        return request
                .header(HttpHeaders.ORIGIN, "http://localhost")
                .header("Sec-Fetch-Site", "same-origin");
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
        @Override
        public long sessionVersion() {
            return 0;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordEncoderTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }
}
