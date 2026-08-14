package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.auth.AccountOAuth2UserService;
import com.personal.baton.adapter.in.web.auth.AccountAuthenticationFailureHandler;
import com.personal.baton.adapter.in.web.auth.AccountSessionSecurityContextRepository;
import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.auth.AuthRateLimiter;
import com.personal.baton.adapter.in.web.auth.AvailableClientAuthorizationRequestResolver;
import com.personal.baton.adapter.in.web.auth.DiscardingOAuth2AuthorizedClientRepository;
import com.personal.baton.adapter.in.web.auth.LocalAccountUserDetailsService;
import com.personal.baton.adapter.in.web.auth.LocalLoginRateLimitFilter;
import com.personal.baton.adapter.in.web.auth.OAuthBrowserAuthenticationFailureHandler;
import com.personal.baton.adapter.in.web.auth.SameOriginSessionMutationFilter;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import com.personal.baton.adapter.in.web.roundauth.RoundGrantAdmissionFilter;
import com.personal.baton.adapter.in.web.security.AccountSessionRequestMatchers;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthFeatureProperties.class)
public class SecurityConfig {

    private static final ErrorResponse AUTHENTICATION_REQUIRED = new ErrorResponse(
            "AUTHENTICATION_REQUIRED",
            "BATON 계정 로그인이 필요합니다"
    );
    private static final ErrorResponse REQUEST_FORBIDDEN = new ErrorResponse(
            "REQUEST_FORBIDDEN",
            "요청을 허용할 수 없습니다"
    );
    private static final ErrorResponse INVALID_CREDENTIALS = new ErrorResponse(
            "INVALID_CREDENTIALS",
            "이메일 또는 비밀번호가 올바르지 않습니다"
    );
    @Bean
    LocalAccountUserDetailsService localAccountUserDetailsService(
            ObjectProvider<LoadLocalCredentialUseCase> loadLocalCredentialUseCaseProvider,
            ObjectProvider<UpdateLocalCredentialPasswordUseCase>
                    updateLocalCredentialPasswordUseCaseProvider
    ) {
        return new LocalAccountUserDetailsService(
                loadLocalCredentialUseCaseProvider,
                updateLocalCredentialPasswordUseCaseProvider
        );
    }

    @Bean
    DaoAuthenticationProvider localAccountAuthenticationProvider(
            LocalAccountUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsPasswordService(userDetailsService);
        return provider;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repository =
                new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return new AccountSessionSecurityContextRepository(repository);
    }

    @Bean
    AuthRateLimiter authRateLimiter() {
        return new AuthRateLimiter();
    }

    @Bean
    SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
        return new SecurityErrorResponseWriter(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<WatchEventReceiverAuthentication> receiverAuthenticationProvider,
            ObjectProvider<SocialLoginProviderCatalog> socialLoginProviderCatalogProvider,
            ObjectProvider<ResolveExternalLoginUseCase> resolveExternalLoginUseCaseProvider,
            ObjectProvider<OidcUserService> oidcUserServiceProvider,
            ObjectProvider<DefaultOAuth2UserService> oauth2UserServiceProvider,
            ObjectProvider<RestClientAuthorizationCodeTokenResponseClient>
                    tokenResponseClientProvider,
            DaoAuthenticationProvider localAccountAuthenticationProvider,
            AuthRateLimiter authRateLimiter,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SecurityErrorResponseWriter errorResponseWriter
    ) throws Exception {
        WatchEventReceiverAuthentication receiverAuthentication = receiverAuthenticationProvider
                .getIfAvailable(WatchEventReceiverAuthentication::disabled);
        SocialLoginProviderCatalog socialLoginProviderCatalog =
                socialLoginProviderCatalogProvider.getIfAvailable();
        ClientRegistrationRepository clientRegistrationRepository =
                socialLoginProviderCatalog == null
                        ? null
                        : socialLoginProviderCatalog.registrations();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(
                                "/api/v1/workspaces",
                                "/api/v1/teams/*/seasons/*/**",
                                WatchHealthEventController.PATH
                        ))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl(AuthController.LOGOUT_PATH)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                            response.setHeader("Cache-Control", "no-store");
                        })
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (AccountSessionRequestMatchers
                                    .accountSessionRequired()
                                    .matches(request)) {
                                errorResponseWriter.write(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        AUTHENTICATION_REQUIRED
                                );
                                return;
                            }
                            errorResponseWriter.write(
                                    response,
                                    HttpServletResponse.SC_FORBIDDEN,
                                    REQUEST_FORBIDDEN
                            );
                        })
                        .accessDeniedHandler((request, response, exception) -> errorResponseWriter.write(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                REQUEST_FORBIDDEN
                        )))
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(
                                    "/actuator/health",
                                    "/api/v1/system/status"
                            ).permitAll()
                            .requestMatchers(HttpMethod.GET,
                                    AuthController.CSRF_PATH,
                                    AuthController.SESSION_PATH,
                                    AuthController.PROVIDERS_PATH
                            ).permitAll()
                            .requestMatchers(HttpMethod.POST,
                                    AuthController.LOCAL_REGISTRATIONS_PATH,
                                    AuthController.LOCAL_EMAIL_VERIFICATIONS_PATH,
                                    AuthController.LOCAL_SESSION_PATH,
                                    AuthController.LOGOUT_PATH
                            ).permitAll()
                            .requestMatchers(
                                    HttpMethod.GET,
                                    ParticipationGrantController.JWK_SET_PATH
                            ).permitAll()
                            .requestMatchers(
                                    HttpMethod.GET,
                                    RoundAdministrationController.CURRENT_MEMBERSHIP_PATH,
                                    RoundAdministrationController.ROOM_MAPPINGS_PATH
                            ).authenticated()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    ParticipationGrantController.REFRESH_PATH_PATTERN,
                                    RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH,
                                    RoundAdministrationController.ROOM_MAPPINGS_PATH
                            ).authenticated()
                            .requestMatchers(
                                    HttpMethod.DELETE,
                                    RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN
                            ).authenticated()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    "/api/v1/workspaces"
                            ).permitAll()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    WatchHealthEventController.PATH
                            ).permitAll()
                            .requestMatchers(
                                    "/api/v1/teams/*/seasons/*/**"
                            ).permitAll();
                    if (clientRegistrationRepository != null) {
                        authorize.requestMatchers(
                                "/oauth2/authorization/*",
                                "/login/oauth2/code/*"
                        ).permitAll();
                    }
                    authorize.anyRequest().denyAll();
                })
                .addFilterBefore(
                        new RoundGrantAdmissionFilter(authRateLimiter, errorResponseWriter),
                        CsrfFilter.class
                )
                .addFilterBefore(
                        new SameOriginSessionMutationFilter(errorResponseWriter),
                        RoundGrantAdmissionFilter.class
                )
                .addFilterBefore(
                        new LocalLoginRateLimitFilter(authRateLimiter, errorResponseWriter),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        new WatchEventReceiverAuthenticationFilter(receiverAuthentication),
                        AnonymousAuthenticationFilter.class
                );

        http.authenticationProvider(localAccountAuthenticationProvider)
                .formLogin(form -> form
                        .loginPage(AuthController.LOCAL_SESSION_PATH)
                        .loginProcessingUrl(AuthController.LOCAL_SESSION_PATH)
                        .usernameParameter("email")
                        .securityContextRepository(securityContextRepository)
                        .successHandler((request, response, authentication) -> {
                            authRateLimiter.recordLoginSuccess(request.getParameter("email"));
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                            response.setHeader("Cache-Control", "no-store");
                        })
                        .failureHandler(new AccountAuthenticationFailureHandler(
                                errorResponseWriter,
                                INVALID_CREDENTIALS,
                                authRateLimiter
                        ))
                        .permitAll());

        if (clientRegistrationRepository != null) {
            ResolveExternalLoginUseCase resolveExternalLoginUseCase =
                    resolveExternalLoginUseCaseProvider.getObject();
            AccountOAuth2UserService accountOAuth2UserService = new AccountOAuth2UserService(
                    resolveExternalLoginUseCase,
                    oidcUserServiceProvider.getObject(),
                    oauth2UserServiceProvider.getObject()
            );
            DiscardingOAuth2AuthorizedClientRepository authorizedClientRepository =
                    new DiscardingOAuth2AuthorizedClientRepository();
            AvailableClientAuthorizationRequestResolver authorizationRequestResolver =
                    new AvailableClientAuthorizationRequestResolver(
                            clientRegistrationRepository
                    );
            http.oauth2Login(oauth2 -> oauth2
                    .clientRegistrationRepository(clientRegistrationRepository)
                    .authorizedClientRepository(authorizedClientRepository)
                    .securityContextRepository(securityContextRepository)
                    .authorizationEndpoint(endpoint -> endpoint
                            .authorizationRequestResolver(authorizationRequestResolver))
                    .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(
                            tokenResponseClientProvider.getObject()
                    ))
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(accountOAuth2UserService::loadOidcUser)
                            .userService(accountOAuth2UserService::loadOAuth2User))
                    .successHandler((request, response, authentication) ->
                            response.sendRedirect("/login"))
                    .failureHandler(new OAuthBrowserAuthenticationFailureHandler()));
        }

        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }
}
