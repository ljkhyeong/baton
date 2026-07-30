package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BatonOidcUserService;
import com.personal.baton.adapter.in.web.identity.IdentitySessionController;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = IdentitySessionController.class,
        properties = "baton.identity.oidc.enabled=true"
)
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        OidcLoginSecurityConfiguration.class,
        BatonOidcUserService.class,
        OidcLoginSecurityTest.ClientRegistrationTestConfig.class
})
class OidcLoginSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private OidcIdentityUseCase oidcIdentityUseCase;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T12:00:00Z"));
    }

    @DisplayName("OIDC 인증 시작은 API 경로 callback과 PKCE를 포함해 공급자로 이동한다")
    @Test
    void startsOidcAuthorizationWithApiCallbackAndPkce() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/auth/oidc/authorization/google"))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        MultiValueMap<String, String> query = UriComponentsBuilder
                .fromUriString(location)
                .build()
                .getQueryParams();
        assertThat(location)
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("http://localhost/api/v1/auth/oidc/callback/google");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("state")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @DisplayName("공급자가 OIDC callback을 거절하면 세션을 폐기하고 JSON 401을 반환한다")
    @Test
    void returnsJsonUnauthorizedForProviderCallbackFailure() throws Exception {
        MvcResult authorization = mockMvc.perform(
                        get("/api/v1/auth/oidc/authorization/google"))
                .andExpect(status().isFound())
                .andReturn();
        String state = UriComponentsBuilder
                .fromUriString(authorization.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("state");
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) authorization
                        .getRequest()
                        .getSession(false);

        mockMvc.perform(get("/api/v1/auth/oidc/callback/google")
                        .session(session)
                        .queryParam("error", "access_denied")
                        .queryParam("state", state))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message")
                        .value("OIDC 로그인을 완료하지 못했습니다"));

        assertThat(session.isInvalid()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClientRegistrationTestConfig {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration google = ClientRegistration
                    .withRegistrationId("google")
                    .clientId("client-id")
                    .clientSecret("client-secret")
                    .clientAuthenticationMethod(
                            ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(
                            AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(
                            "{baseUrl}/api/v1/auth/oidc/callback/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri(
                            "https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .jwkSetUri(
                            "https://www.googleapis.com/oauth2/v3/certs")
                    .issuerUri("https://accounts.google.com")
                    .userNameAttributeName("sub")
                    .clientName("Google")
                    .build();
            return new InMemoryClientRegistrationRepository(google);
        }
    }
}
