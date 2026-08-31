package com.personal.baton.adapter.in.web.auth;

import java.util.List;
import java.util.UUID;

public final class AuthResponses {

    private AuthResponses() {
    }

    public record CsrfResponse(
            String csrfHeaderName,
            String csrfToken
    ) {
    }

    public record UnauthenticatedSessionResponse(boolean authenticated) {

        public UnauthenticatedSessionResponse() {
            this(false);
        }
    }

    public record AuthenticatedSessionResponse(
            boolean authenticated,
            UUID accountId,
            String csrfHeaderName,
            String csrfToken
    ) {

        public AuthenticatedSessionResponse(
                UUID accountId,
                String csrfHeaderName,
                String csrfToken
        ) {
            this(true, accountId, csrfHeaderName, csrfToken);
        }
    }

    public record LocalRegistrationResponse(boolean verificationRequired) {

        public LocalRegistrationResponse() {
            this(true);
        }
    }

    public record AuthProvidersResponse(
            List<String> providers,
            boolean localRegistrationEnabled,
            boolean passwordResetEnabled
    ) {

        public AuthProvidersResponse {
            providers = List.copyOf(providers);
        }
    }

    public record PasswordResetRequestResponse(boolean accepted) {
    }
}
