package com.personal.baton.application.identity.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface OidcIdentityUseCase {

    OidcAccountResult resolveAccount(VerifiedOidcIdentity verifiedIdentity);

    record VerifiedOidcIdentity(String issuer, String subject) {

        public VerifiedOidcIdentity {
            Objects.requireNonNull(issuer, "검증된 OIDC issuer는 필수입니다");
            Objects.requireNonNull(subject, "검증된 OIDC subject는 필수입니다");
        }
    }

    record OidcAccountResult(UUID accountId, Instant createdAt) {
    }
}
