package com.personal.baton.adapter.in.web.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "baton.identity.oidc.enabled", havingValue = "true")
class OidcLoginSecurityConfiguration {

    static final String AUTHORIZATION_BASE_URI =
            "/api/v1/auth/oidc/authorization";
    static final String CALLBACK_BASE_URI =
            "/api/v1/auth/oidc/callback";

    @Bean
    OidcLoginSecurityConfigurer oidcLoginSecurityConfigurer(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2UserService<OidcUserRequest, OidcUser> batonOidcUserService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        DefaultOAuth2AuthorizationRequestResolver authorizationRequestResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        AUTHORIZATION_BASE_URI
                );
        authorizationRequestResolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce()
        );

        return (http, sessionSecurityContextRepository) ->
                http.oauth2Login(oauth2 -> oauth2
                .clientRegistrationRepository(clientRegistrationRepository)
                .authorizedClientRepository(new NoOpOAuth2AuthorizedClientRepository())
                .securityContextRepository(
                        new RequestAttributeSecurityContextRepository())
                .successHandler(new BatonOidcAuthenticationSuccessHandler(
                        sessionSecurityContextRepository,
                        clock))
                .failureHandler(new BatonOidcAuthenticationFailureHandler(
                        new SecurityErrorResponseWriter(objectMapper)))
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestResolver(authorizationRequestResolver))
                .redirectionEndpoint(endpoint -> endpoint
                        .baseUri(CALLBACK_BASE_URI + "/*"))
                .userInfoEndpoint(endpoint -> endpoint
                        .oidcUserService(batonOidcUserService)));
    }
}
