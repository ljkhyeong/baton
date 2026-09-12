package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.identity.BrevoEmailEventController;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class BrevoWebhookSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain brevoWebhookSecurityFilterChain(
            HttpSecurity http, ObjectMapper objectMapper,
            @Value("${baton.brevo.webhook.enabled:false}") boolean enabled,
            @Value("${baton.brevo.webhook.bearer-token:}") String token
    ) throws Exception {
        if (enabled && !token.matches("[A-Za-z0-9._~-]{32,200}")) {
            throw new IllegalArgumentException("Brevo 웹훅 토큰은 32~200자의 URL-safe ASCII여야 합니다");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(objectMapper);
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writer.write(response, 401, new ErrorResponse("UNAUTHORIZED", "인증 정보가 올바르지 않습니다"));
        };
        return http.securityMatcher(BrevoEmailEventController.PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(unauthorized)
                        .opaqueToken(opaque -> opaque.introspector(presented -> {
                            if (!enabled || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
                                throw new BadOpaqueTokenException("인증 정보가 올바르지 않습니다");
                            }
                            return new DefaultOAuth2AuthenticatedPrincipal("brevo", Map.of("sub", "brevo"), List.of());
                        })))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
                .build();
    }
}
