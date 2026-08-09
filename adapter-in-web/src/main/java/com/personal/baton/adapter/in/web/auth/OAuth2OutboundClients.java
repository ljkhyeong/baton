package com.personal.baton.adapter.in.web.auth;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

public final class OAuth2OutboundClients {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestTemplateBuilder restTemplateBuilder;
    private final RestClient.Builder restClientBuilder;

    public OAuth2OutboundClients(
            RestTemplateBuilder restTemplateBuilder,
            RestClient.Builder restClientBuilder
    ) {
        this.restTemplateBuilder = Objects.requireNonNull(restTemplateBuilder)
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT);
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder).clone();
    }

    public DefaultOAuth2UserService oauth2UserService() {
        RestTemplate restOperations = restTemplateBuilder
                .errorHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        DefaultOAuth2UserService userService = new DefaultOAuth2UserService();
        userService.setRestOperations(restOperations);
        return userService;
    }

    public OidcUserService oidcUserService(DefaultOAuth2UserService oauth2UserService) {
        OidcUserService userService = new OidcUserService();
        userService.setOauth2UserService(Objects.requireNonNull(oauth2UserService));
        return userService;
    }

    public JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory() {
        return registration -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(restTemplateBuilder.build())
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                    new OidcIdTokenValidator(registration)
            ));
            decoder.setClaimSetConverter(
                    OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter()
            );
            return decoder;
        };
    }

    public RestClientAuthorizationCodeTokenResponseClient tokenResponseClient() {
        RestClient restClient = restClientBuilder.clone()
                .requestFactory(restTemplateBuilder.buildRequestFactory())
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(
                            new OAuth2AccessTokenResponseHttpMessageConverter()
                    );
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }
}
