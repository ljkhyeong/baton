package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.identity.ResolvedBatonOidcUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcSessionPrincipalTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant AUTHENTICATED_AT =
            Instant.parse("2026-07-30T12:00:00Z");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @DisplayName("OIDC 로그인 성공 뒤 세션에는 공급자 principal 대신 BATON 계정 principal만 저장한다")
    @Test
    void persistsOnlyCompactBatonAccountPrincipal() throws Exception {
        HttpSessionSecurityContextRepository repository =
                new HttpSessionSecurityContextRepository();
        BatonOidcAuthenticationSuccessHandler handler =
                successHandler(repository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResolvedBatonOidcUser oidcUser = mock(ResolvedBatonOidcUser.class);
        when(oidcUser.accountId()).thenReturn(ACCOUNT_ID);
        OAuth2AuthenticationToken oidcAuthentication =
                new OAuth2AuthenticationToken(oidcUser, List.of(), "google");

        handler.onAuthenticationSuccess(request, response, oidcAuthentication);

        SecurityContext savedContext = (SecurityContext) request.getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(savedContext.getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(savedContext.getAuthentication().getPrincipal())
                .isEqualTo(new BatonAccountPrincipal(ACCOUNT_ID))
                .isNotInstanceOf(OidcUser.class);
        assertThat(savedContext.getAuthentication().getCredentials()).isNull();
        assertThat(request.getSession(false).getAttribute(
                AbsoluteSessionLifetimeFilter.AUTHENTICATED_AT_SESSION_ATTRIBUTE
        )).isEqualTo(AUTHENTICATED_AT.toEpochMilli());
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @DisplayName("BATON 계정으로 해석하지 못한 OIDC 성공 결과는 세션을 폐기한다")
    @Test
    void invalidatesSessionWhenResolvedPrincipalIsMissing() {
        HttpSessionSecurityContextRepository repository =
                new HttpSessionSecurityContextRepository();
        BatonOidcAuthenticationSuccessHandler handler =
                successHandler(repository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        UsernamePasswordAuthenticationToken unexpectedAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "provider-principal",
                        null,
                        List.of()
                );

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(
                request,
                response,
                unexpectedAuthentication
        )).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @DisplayName("OIDC callback 실패는 세션을 폐기하고 redirect 없이 JSON 401을 반환한다")
    @Test
    void returnsJsonUnauthorizedAndInvalidatesSessionOnOidcFailure()
            throws Exception {
        BatonOidcAuthenticationFailureHandler handler =
                new BatonOidcAuthenticationFailureHandler(
                        new SecurityErrorResponseWriter(new ObjectMapper())
                );
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.createEmptyContext()
        );

        handler.onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_authorization_response")
                )
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getContentAsString())
                .contains("\"code\":\"AUTHENTICATION_REQUIRED\"")
                .doesNotContain("invalid_authorization_response");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private BatonOidcAuthenticationSuccessHandler successHandler(
            HttpSessionSecurityContextRepository repository
    ) {
        return new BatonOidcAuthenticationSuccessHandler(
                repository,
                Clock.fixed(AUTHENTICATED_AT, ZoneOffset.UTC)
        );
    }
}
