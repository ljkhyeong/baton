package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(12);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Clock clock,
            ObjectMapper objectMapper,
            ObjectProvider<OidcLoginSecurityConfigurer> oidcConfigurerProvider,
            @Value("${baton.identity.oidc.enabled:false}") boolean oidcEnabled
    ) throws Exception {
        OidcLoginSecurityConfigurer oidcConfigurer = oidcConfigurerProvider.getIfAvailable();
        SecurityContextRepository sessionSecurityContextRepository =
                new HttpSessionSecurityContextRepository();
        if (oidcConfigurer != null) {
            oidcConfigurer.configure(http, sessionSecurityContextRepository);
        }

        SecurityErrorResponseWriter errorResponseWriter =
                new SecurityErrorResponseWriter(objectMapper);

        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/v1/workspaces",
                        "/api/v1/teams/*/seasons/*/**",
                        "/api/v1/identity/bootstrap-invitations"
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
                        .requestMatchers("/actuator/health", "/api/v1/system/status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/identity/bootstrap-invitations"
                        ).permitAll()
                        .requestMatchers("/api/v1/teams/*/seasons/*/**").permitAll()
                        .requestMatchers(
                                "/api/v1/me",
                                "/api/v1/identity/invitations/accept",
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
                .build();
    }
}
