package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

// 서비스 간 수신 경로의 고정 Bearer 토큰을 Spring Security 리소스 서버(opaque token)로 확인한다.
final class StaticBearerTokenChains {

    private static final String INVALID_CREDENTIALS = "인증 정보가 올바르지 않습니다";
    private static final ErrorResponse UNAUTHORIZED = new ErrorResponse("UNAUTHORIZED", INVALID_CREDENTIALS);

    private StaticBearerTokenChains() {
    }

    static SecurityFilterChain build(
            HttpSecurity http,
            RequestMatcher matcher,
            BearerTokenResolver tokenResolver,
            Predicate<String> acceptsToken,
            String subject,
            SecurityErrorResponseWriter errorResponseWriter
    ) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            errorResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
        };
        return http.securityMatcher(matcher)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(unauthorized)
                        .bearerTokenResolver(tokenResolver)
                        .opaqueToken(opaque -> opaque.introspector(token -> {
                            if (!acceptsToken.test(token)) {
                                throw new BadOpaqueTokenException(INVALID_CREDENTIALS);
                            }
                            return new DefaultOAuth2AuthenticatedPrincipal(subject, Map.of("sub", subject), List.of());
                        })))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
                .build();
    }
}
