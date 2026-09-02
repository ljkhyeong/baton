package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.AccountView;
import com.personal.baton.domain.identity.IdentityProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    public record AccountResponse(
            UUID accountId,
            String displayName,
            List<LinkedIdentityResponse> identities
    ) {

        public AccountResponse {
            identities = List.copyOf(identities);
        }

        public static AccountResponse from(AccountView account) {
            return new AccountResponse(
                    account.accountId(),
                    account.displayName(),
                    account.identities().stream()
                            .map(LinkedIdentityResponse::from)
                            .toList()
            );
        }
    }

    public record LinkedIdentityResponse(
            String provider,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String email,
            boolean emailVerified
    ) {

        private static LinkedIdentityResponse from(AccountView.LinkedIdentityView identity) {
            return new LinkedIdentityResponse(
                    providerId(identity.provider()),
                    identity.email(),
                    identity.emailVerified()
            );
        }

        private static String providerId(IdentityProvider provider) {
            return switch (provider) {
                case GOOGLE -> "google";
                case NAVER -> "naver";
                case LOCAL_EMAIL -> "local_email";
            };
        }
    }
}
