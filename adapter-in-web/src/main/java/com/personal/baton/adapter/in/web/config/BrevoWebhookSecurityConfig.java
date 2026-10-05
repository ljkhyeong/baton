package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BrevoEmailEventController;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
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
        return StaticBearerTokenChains.build(
                http,
                PathPatternRequestMatcher.pathPattern(BrevoEmailEventController.PATH),
                new DefaultBearerTokenResolver(),
                presented -> enabled && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8)),
                "brevo",
                new SecurityErrorResponseWriter(objectMapper)
        );
    }
}
