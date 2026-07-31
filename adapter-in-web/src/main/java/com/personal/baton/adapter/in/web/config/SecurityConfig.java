package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(12);
    private static final Pattern TEAM_SEASON_PATH = Pattern.compile(
            "^/api/v1/teams/[^/]+/seasons/[^/]+(?:/.*)?$"
    );
    private static final Pattern ACCESS_KEY_RECOVERY_PATH = Pattern.compile(
            "^/api/v1/teams/[^/]+/seasons/[^/]+/access-key/recover$"
    );
    private static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Clock clock,
            ObjectMapper objectMapper,
            ObjectProvider<OidcLoginSecurityConfigurer> oidcConfigurerProvider,
            @Value("${baton.identity.oidc.enabled:false}") boolean oidcEnabled,
            @Value("${baton.round.grant.issuer:http://localhost:8080}")
            String roundGrantIssuer
    ) throws Exception {
        OidcLoginSecurityConfigurer oidcConfigurer = oidcConfigurerProvider.getIfAvailable();
        SecurityContextRepository sessionSecurityContextRepository =
                new HttpSessionSecurityContextRepository();
        if (oidcConfigurer != null) {
            oidcConfigurer.configure(http, sessionSecurityContextRepository);
        }

        SecurityErrorResponseWriter errorResponseWriter =
                new SecurityErrorResponseWriter(objectMapper);
        RoundGrantOriginFilter roundGrantOriginFilter =
                new RoundGrantOriginFilter(roundGrantIssuer, errorResponseWriter);

        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        SecurityConfig::isWorkspaceCreation,
                        SecurityConfig::isAnonymousOwnedWorkspaceCreation,
                        SecurityConfig::isIdentityBootstrapInvitation,
                        SecurityConfig::isAccessKeyRecovery,
                        SecurityConfig::isAnonymousLegacyTeamSeasonMutation
                ))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache
                        .requestCache(new NullRequestCache()))
                .securityContext(context -> context
                        .securityContextRepository(sessionSecurityContextRepository))
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new JsonAuthenticationEntryPoint(errorResponseWriter))
                        .accessDeniedHandler(
                                new JsonAccessDeniedHandler(errorResponseWriter)))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/api/v1/system/status",
                                "/.well-known/jwks.json"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/identity/bootstrap-invitations"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/teams/*/seasons/*/role-resources/*"
                                        + "/round-participation-grant"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/round/rooms/*/participation-grant"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/round/rooms/*/participation-grant/refresh"
                        ).authenticated()
                        .requestMatchers("/api/v1/teams/*/seasons/*/**").permitAll()
                        .requestMatchers(
                                "/api/v1/me",
                                "/api/v1/me/workspaces",
                                "/api/v1/identity/invitations/preview",
                                "/api/v1/identity/invitations/accept",
                                "/api/v1/teams/*/membership",
                                "/api/v1/teams/*/member-invitations",
                                "/api/v1/teams/*/member-invitations/*/revocation",
                                "/api/v1/session/logout"
                        ).authenticated()
                        .requestMatchers(
                                oidcEnabled
                                        ? OidcLoginSecurityConfiguration.AUTHORIZATION_BASE_URI
                                                + "/*"
                                        : "/__baton_oidc_disabled__",
                                oidcEnabled
                                        ? OidcLoginSecurityConfiguration.CALLBACK_BASE_URI + "/*"
                                        : "/__baton_oidc_disabled__"
                        ).permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new AbsoluteSessionLifetimeFilter(clock, ABSOLUTE_SESSION_LIFETIME),
                        SecurityContextHolderFilter.class
                )
                .addFilterBefore(roundGrantOriginFilter, CsrfFilter.class)
                .build();
    }

    private static boolean isWorkspaceCreation(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().equals("/api/v1/workspaces");
    }

    private static boolean isAnonymousOwnedWorkspaceCreation(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().equals("/api/v1/me/workspaces")
                && isAnonymous();
    }

    private static boolean isIdentityBootstrapInvitation(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().equals(
                        "/api/v1/identity/bootstrap-invitations"
                );
    }

    private static boolean isAccessKeyRecovery(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && ACCESS_KEY_RECOVERY_PATH.matcher(request.getRequestURI()).matches();
    }

    private static boolean isAnonymousLegacyTeamSeasonMutation(
            HttpServletRequest request
    ) {
        String accessKey = request.getHeader(ACCESS_KEY_HEADER);
        return TEAM_SEASON_PATH.matcher(request.getRequestURI()).matches()
                && !RoundGrantOriginFilter.isGrantPath(request)
                && accessKey != null
                && !accessKey.isBlank()
                && isAnonymous();
    }

    private static boolean isAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }
}
