package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.WebAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthBrowserAuthenticationFailureHandlerTest {

    private final OAuthBrowserAuthenticationFailureHandler handler =
            new OAuthBrowserAuthenticationFailureHandler();

    @DisplayName("일반 OAuth 실패는 provider 세부 정보 없이 고정 login_failed로 이동한다")
    @Test
    void redirectsGenericFailureWithoutProviderDetails() throws Exception {
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(new OAuth2Error(
                        "access_denied",
                        "provider detail must stay private",
                        "https://provider.example/error"
                ))
        );

        assertFixedRedirect(
                request,
                response,
                OAuthBrowserAuthenticationFailureHandler.LOGIN_FAILED_REDIRECT
        );
    }

    @DisplayName("identity 인프라 OAuth 실패는 고정 temporarily_unavailable로 이동한다")
    @Test
    void redirectsInfrastructureFailureToTemporaryError() throws Exception {
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IdentityOperationUnavailableException failure =
                new IdentityOperationUnavailableException(
                        "identity repository unavailable",
                        new IllegalStateException("jdbc:mysql://private-host/identity")
                );

        handler.onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("identity_infrastructure_unavailable"),
                        failure
                )
        );

        assertFixedRedirect(
                request,
                response,
                OAuthBrowserAuthenticationFailureHandler.TEMPORARILY_UNAVAILABLE_REDIRECT
        );
    }

    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/login/oauth2/code/google"
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("oauth-authorization-request", "preserved");
        request.setSession(session);
        return request;
    }

    private void assertFixedRedirect(
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            String expectedLocation
    ) throws Exception {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(expectedLocation);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(
                OAuthBrowserAuthenticationFailureHandler.REFERRER_POLICY_HEADER
        )).isEqualTo("no-referrer");
        assertThat(response.getContentAsString())
                .doesNotContain("provider detail", "private-host", "access_denied");
        assertThat(request.getSession(false).getAttribute(
                WebAttributes.AUTHENTICATION_EXCEPTION
        )).isNull();
        assertThat(Collections.list(request.getSession(false).getAttributeNames()))
                .containsExactly("oauth-authorization-request");
    }
}
