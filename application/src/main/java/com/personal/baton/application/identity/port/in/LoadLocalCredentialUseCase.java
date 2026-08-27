package com.personal.baton.application.identity.port.in;

import java.util.Optional;
import java.util.UUID;

public interface LoadLocalCredentialUseCase {

    Optional<LocalCredentialResult> loadLocalCredential(String email);

    record LocalCredentialResult(
            UUID accountId,
            String passwordHash,
            boolean emailVerified
    ) {

        @Override
        public String toString() {
            return "LocalCredentialResult[accountId=" + accountId
                    + ", passwordHash=[REDACTED], emailVerified=" + emailVerified + "]";
        }
    }
}
