package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.AccountView;
import com.personal.baton.domain.identity.IdentityProvider;

public interface ResolveExternalLoginUseCase {

    ExternalLoginResult resolveExternalLogin(ExternalLoginCommand command);

    record ExternalLoginCommand(
            IdentityProvider provider,
            String providerSubject,
            String email,
            boolean emailVerified,
            String displayName
    ) {

        @Override
        public String toString() {
            return "ExternalLoginCommand[provider=" + provider
                    + ", providerSubject=[REDACTED], email=[REDACTED], emailVerified="
                    + emailVerified + ", displayName=[REDACTED]]";
        }
    }

    record ExternalLoginResult(
            AccountView account,
            boolean created
    ) {
    }
}
