package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.auth.OAuth2OutboundClients;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
@ConditionalOnProperty(
        prefix = "baton.auth.oauth2",
        name = "enabled",
        havingValue = "true"
)
public class SocialLoginConfiguration {

    @Bean
    JwtDecoderFactory<ClientRegistration> boundedOidcIdTokenDecoderFactory() {
        return OAuth2OutboundClients.oidcIdTokenDecoderFactory();
    }

    @Bean
    ClientRegistrationRepository batonClientRegistrationRepository(
            SocialLoginProperties properties
    ) {
        List<ClientRegistration> registrations = new ArrayList<>();
        addGoogleRegistration(registrations, properties.getGoogle());
        addNaverRegistration(registrations, properties.getNaver());
        if (registrations.isEmpty()) {
            throw new IllegalStateException(
                    "OAuth2 로그인이 활성화됐지만 완전한 Google/Naver credential이 없습니다"
            );
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    private void addGoogleRegistration(
            List<ClientRegistration> registrations,
            SocialLoginProperties.ProviderCredentials credentials
    ) {
        if (!validatePair("Google", credentials)) {
            return;
        }
        registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(credentials.getClientId())
                .clientSecret(credentials.getClientSecret())
                .scope("openid", "profile", "email")
                .build());
    }

    private void addNaverRegistration(
            List<ClientRegistration> registrations,
            SocialLoginProperties.ProviderCredentials credentials
    ) {
        if (!validatePair("Naver", credentials)) {
            return;
        }
        registrations.add(ClientRegistration.withRegistrationId("naver")
                .clientId(credentials.getClientId())
                .clientSecret(credentials.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .tokenUri("https://nid.naver.com/oauth2.0/token")
                .userInfoUri("https://openapi.naver.com/v1/nid/me")
                .userNameAttributeName("response")
                .clientName("Naver")
                .build());
    }

    private boolean validatePair(
            String provider,
            SocialLoginProperties.ProviderCredentials credentials
    ) {
        boolean clientIdPresent = StringUtils.hasText(credentials.getClientId());
        boolean clientSecretPresent = StringUtils.hasText(credentials.getClientSecret());
        if (clientIdPresent != clientSecretPresent) {
            throw new IllegalStateException(
                    provider + " OAuth2 client-id와 client-secret은 함께 구성해야 합니다"
            );
        }
        return clientIdPresent;
    }
}
