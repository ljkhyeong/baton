package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.SocialLoginProviderCatalog;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.identity.AccountView;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginResult;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        ConfiguredSocialLoginSecurityTest.SocialLoginTestConfig.class
})
class ConfiguredSocialLoginSecurityTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String PROVIDER_ID_TOKEN = "provider-id-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RestClientAuthorizationCodeTokenResponseClient tokenResponseClient;

    @Autowired
    private OidcUserService oidcUserService;

    @Autowired
    private JwtDecoder oidcJwtDecoder;

    @MockitoBean
    private RegisterLocalAccountUseCase registerLocalAccountUseCase;

    @MockitoBean
    private VerifyLocalEmailUseCase verifyLocalEmailUseCase;

    @MockitoBean
    private LoadLocalCredentialUseCase loadLocalCredentialUseCase;

    @MockitoBean
    private ResolveExternalLoginUseCase resolveExternalLoginUseCase;

    @DisplayName("credential이 구성된 Google OIDC 시작 경로만 provider authorization으로 이동한다")
    @Test
    void exposesConfiguredGoogleAuthorization() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                ));
    }

    @DisplayName("provider 목록에는 repository에 구성된 Google만 고정 allowlist로 노출한다")
    @Test
    void exposesOnlyConfiguredProvider() throws Exception {
        mockMvc.perform(get(AuthController.PROVIDERS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(
                                "{\"providers\":[\"google\"],"
                                        + "\"localRegistrationEnabled\":false}",
                                true
                        ));
    }

    @DisplayName("같은 repository에 없는 Naver 등록은 authorization redirect를 노출하지 않는다")
    @Test
    void omitsUnconfiguredNaverAuthorization() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/naver"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Location"));
    }

    @DisplayName("OAuth callback 실패는 provider 설명 없이 고정 login_failed로 이동한다")
    @Test
    void redirectsCallbackFailureToFixedBrowserError() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("preserved", "session value");

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("error", "access_denied")
                        .param("error_description", "provider detail must stay private"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        OAuthBrowserAuthenticationFailureHandler.LOGIN_FAILED_REDIRECT
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        OAuthBrowserAuthenticationFailureHandler.REFERRER_POLICY_HEADER,
                        "no-referrer"
                ))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("provider detail", "access_denied"));

        assertThat(session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(session.getAttribute("preserved")).isEqualTo("session value");
    }

    @DisplayName("실제 OIDC callback filter는 provider token을 저장하지 않고 canonical account session만 남긴다")
    @Test
    void persistsOnlyCanonicalAccountAuthenticationAfterOidcCallback() throws Exception {
        RecordingMockHttpSession session = new RecordingMockHttpSession();
        MvcResult authorizationResult = mockMvc.perform(
                        get("/oauth2/authorization/google").session(session)
                )
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String authorizationLocation = authorizationResult.getResponse().getHeader("Location");
        assertThat(authorizationLocation).isNotBlank();
        Map<String, String> authorizationParameters = UriComponentsBuilder
                .fromUriString(authorizationLocation)
                .build()
                .getQueryParams()
                .toSingleValueMap();
        String state = UriUtils.decode(
                authorizationParameters.get("state"),
                StandardCharsets.UTF_8
        );
        String nonce = UriUtils.decode(
                authorizationParameters.get("nonce"),
                StandardCharsets.UTF_8
        );
        assertThat(state).isNotBlank();
        assertThat(nonce).isNotBlank();

        when(tokenResponseClient.getTokenResponse(
                any(OAuth2AuthorizationCodeGrantRequest.class)
        )).thenReturn(tokenResponse());
        when(oidcJwtDecoder.decode(PROVIDER_ID_TOKEN)).thenReturn(idTokenJwt(nonce));
        when(oidcUserService.loadUser(any(OidcUserRequest.class)))
                .thenAnswer(invocation -> providerUser(invocation.getArgument(0)));
        when(resolveExternalLoginUseCase.resolveExternalLogin(any())).thenReturn(
                new ExternalLoginResult(
                        new AccountView(ACCOUNT_ID, "Google Member", List.of()),
                        true
                )
        );

        MvcResult callbackResult = mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "authorization-code")
                        .param("state", state))
                .andReturn();

        verify(tokenResponseClient).getTokenResponse(
                any(OAuth2AuthorizationCodeGrantRequest.class)
        );
        verify(oidcJwtDecoder).decode(PROVIDER_ID_TOKEN);
        verify(oidcUserService).loadUser(any(OidcUserRequest.class));
        verify(resolveExternalLoginUseCase).resolveExternalLogin(any());
        assertThat(callbackResult.getResponse().getStatus()).isBetween(300, 399);
        assertThat(callbackResult.getResponse().getRedirectedUrl()).isEqualTo("/login");

        SecurityContext persistedContext = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
        assertThat(persistedContext).isNotNull();
        assertCanonicalAccountAuthentication(persistedContext.getAuthentication());
        assertThat(session.securityContextWrites()).isNotEmpty();
        session.securityContextWrites().forEach(this::assertCanonicalAccountAuthentication);
        assertThat(session.attributeWrites()).noneMatch(value ->
                value instanceof OAuth2AuthenticationToken
                        || value instanceof OAuth2AuthorizedClient
                        || value instanceof OidcUser
                        || value instanceof OidcIdToken
        );
        assertThat(Collections.list(session.getAttributeNames())).containsExactly(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
    }

    private OAuth2AccessTokenResponse tokenResponse() {
        return OAuth2AccessTokenResponse.withToken("provider-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(300)
                .scopes(Set.of("openid", "profile", "email"))
                .additionalParameters(Map.of(
                        OidcParameterNames.ID_TOKEN,
                        PROVIDER_ID_TOKEN
                ))
                .build();
    }

    private Jwt idTokenJwt(String nonce) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(PROVIDER_ID_TOKEN)
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .subject("google-subject")
                .audience(List.of("google-client"))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .claim("nonce", nonce)
                .claim("email", "member@example.com")
                .claim("email_verified", true)
                .claim("name", "Google Member")
                .claim("provider_secret_claim", "must-not-enter-session")
                .build();
    }

    private OidcUser providerUser(OidcUserRequest request) {
        OidcUser providerUser = mock(OidcUser.class);
        OidcIdToken idToken = request.getIdToken();
        OidcUserInfo userInfo = OidcUserInfo.builder()
                .subject("google-subject")
                .claim("private_profile_claim", "must-not-enter-session")
                .build();
        when(providerUser.getIdToken()).thenReturn(idToken);
        when(providerUser.getUserInfo()).thenReturn(userInfo);
        when(providerUser.getClaims()).thenReturn(idToken.getClaims());
        when(providerUser.getAttributes()).thenReturn(idToken.getClaims());
        when(providerUser.getSubject()).thenReturn("google-subject");
        when(providerUser.getEmail()).thenReturn("member@example.com");
        when(providerUser.getEmailVerified()).thenReturn(true);
        when(providerUser.getFullName()).thenReturn("Google Member");
        return providerUser;
    }

    private void assertCanonicalAccountAuthentication(Authentication authentication) {
        assertThat(authentication)
                .isNotNull()
                .isNotInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(authentication.getPrincipal())
                .isInstanceOf(AccountSessionPrincipal.class)
                .isNotInstanceOf(OidcUser.class);
        AccountSessionPrincipal principal =
                (AccountSessionPrincipal) authentication.getPrincipal();
        assertThat(principal.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ACCOUNT");
    }

    private static final class RecordingMockHttpSession extends MockHttpSession {

        private final List<Authentication> securityContextWrites = new ArrayList<>();
        private final List<Object> attributeWrites = new ArrayList<>();

        @Override
        public void setAttribute(String name, Object value) {
            attributeWrites.add(value);
            if (HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY.equals(name)
                    && value instanceof SecurityContext context) {
                securityContextWrites.add(context.getAuthentication());
            }
            super.setAttribute(name, value);
        }

        List<Authentication> securityContextWrites() {
            return List.copyOf(securityContextWrites);
        }

        List<Object> attributeWrites() {
            return List.copyOf(attributeWrites);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SocialLoginTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("google-client")
                    .clientSecret("google-secret")
                    .scope("openid", "profile", "email")
                    .build();
            return new InMemoryClientRegistrationRepository(google);
        }

        @Bean
        SocialLoginProviderCatalog socialLoginProviderCatalog(
                ClientRegistrationRepository registrations
        ) {
            return new SocialLoginProviderCatalog(registrations);
        }

        @Bean
        DefaultOAuth2UserService defaultOAuth2UserService() {
            return new DefaultOAuth2UserService();
        }

        @Bean
        OidcUserService oidcUserService() {
            return mock(OidcUserService.class);
        }

        @Bean
        RestClientAuthorizationCodeTokenResponseClient tokenResponseClient() {
            return mock(RestClientAuthorizationCodeTokenResponseClient.class);
        }

        @Bean
        JwtDecoder oidcJwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        JwtDecoderFactory<ClientRegistration> oidcJwtDecoderFactory(
                JwtDecoder oidcJwtDecoder
        ) {
            return registration -> oidcJwtDecoder;
        }
    }
}
