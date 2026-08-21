package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthBrowserAuthenticationFailureHandlerTest {

    private final OAuthBrowserAuthenticationFailureHandler handler =
            new OAuthBrowserAuthenticationFailureHandler();

    @DisplayName("identity 인프라 OAuth 실패는 고정 temporarily_unavailable로 이동한다")
    @Test
    void redirectsInfrastructureFailureToTemporaryError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/login/oauth2/code/google"
        );
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

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                OAuthBrowserAuthenticationFailureHandler.TEMPORARILY_UNAVAILABLE_REDIRECT
        );
    }
}
