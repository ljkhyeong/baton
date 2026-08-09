package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.auth.OAuth2OutboundClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
@ConditionalOnProperty(
        prefix = "baton.auth.oauth2",
        name = "enabled",
        havingValue = "true"
)
public class SocialLoginConfiguration {

    @Bean
    SocialLoginProviderCatalog socialLoginProviderCatalog(
            SocialLoginProperties properties,
            ClientRegistrationRepository registrations
    ) {
        return new SocialLoginProviderCatalog(properties, registrations);
    }

    @Bean
    OAuth2OutboundClients oauth2OutboundClients(
            RestTemplateBuilder restTemplateBuilder,
            RestClient.Builder restClientBuilder
    ) {
        return new OAuth2OutboundClients(restTemplateBuilder, restClientBuilder);
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> boundedOidcIdTokenDecoderFactory(
            OAuth2OutboundClients outboundClients
    ) {
        return outboundClients.oidcIdTokenDecoderFactory();
    }

    @Bean
    DefaultOAuth2UserService defaultOAuth2UserService(
            OAuth2OutboundClients outboundClients
    ) {
        return outboundClients.oauth2UserService();
    }

    @Bean
    OidcUserService oidcUserService(
            OAuth2OutboundClients outboundClients,
            DefaultOAuth2UserService oauth2UserService
    ) {
        return outboundClients.oidcUserService(oauth2UserService);
    }

    @Bean
    RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
            OAuth2OutboundClients outboundClients
    ) {
        return outboundClients.tokenResponseClient();
    }
}
