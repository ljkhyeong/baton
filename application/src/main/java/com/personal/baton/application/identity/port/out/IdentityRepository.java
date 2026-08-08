package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.LocalCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {

    Account saveAccount(Account account);

    AccountIdentity saveIdentity(AccountIdentity identity);

    LocalCredential saveLocalCredential(LocalCredential credential);

    EmailVerificationChallenge saveEmailVerificationChallenge(
            EmailVerificationChallenge challenge
    );

    Optional<Account> findAccountById(UUID accountId);

    Optional<AccountIdentity> findIdentity(IdentityProvider provider, String providerSubject);

    Optional<AccountIdentity> findIdentityByIdForUpdate(UUID identityId);

    List<AccountIdentity> findIdentitiesByAccountId(UUID accountId);

    Optional<LocalCredential> findLocalCredentialByIdentityId(UUID identityId);

    Optional<LocalCredential> findLocalCredentialByIdentityIdForUpdate(UUID identityId);

    Optional<EmailVerificationChallenge> findEmailVerificationChallengeByIdentityIdForUpdate(
            UUID identityId
    );

    Optional<EmailVerificationChallenge> findEmailVerificationChallengeByTokenHashForUpdate(
            String tokenHash
    );
}
