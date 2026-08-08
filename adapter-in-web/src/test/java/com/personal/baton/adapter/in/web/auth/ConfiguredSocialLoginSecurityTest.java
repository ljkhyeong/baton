package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.startsWith;
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

    @Autowired
    private MockMvc mockMvc;

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
    }
}
