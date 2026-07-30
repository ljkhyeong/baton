package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.OidcExternalIdentity;
import com.personal.baton.domain.identity.UserAccount;

public interface OidcIdentityRepository {

    ExternalIdentityResolution resolveOrCreate(
            UserAccount candidateAccount,
            OidcExternalIdentity candidateIdentity
    );

    record ExternalIdentityResolution(
            UserAccount account,
            OidcExternalIdentity externalIdentity
    ) {
    }
}
