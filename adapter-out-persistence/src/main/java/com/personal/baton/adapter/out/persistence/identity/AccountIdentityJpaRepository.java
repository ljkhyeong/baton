package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.IdentityProvider;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountIdentityJpaRepository extends JpaRepository<AccountIdentity, UUID> {

    Optional<AccountIdentity> findByProviderAndProviderSubject(
            IdentityProvider provider,
            String providerSubject
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountIdentity> findForUpdateByProviderAndProviderSubject(
            IdentityProvider provider,
            String providerSubject
    );

    List<AccountIdentity> findAllByAccountIdOrderByProviderAsc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountIdentity> findForUpdateById(UUID identityId);

    @Query("""
            select identity.accountId as accountId, credential.passwordHash as passwordHash,
                   identity.emailVerified as emailVerified, account.sessionVersion as sessionVersion
            from AccountIdentity identity
            join LocalCredential credential on credential.identityId = identity.id
            join Account account on account.id = identity.accountId
            where identity.provider = :provider and identity.providerSubject = :email
            """)
    Optional<LocalLoginCredential> findLoginCredential(IdentityProvider provider, String email);

    interface LocalLoginCredential {
        UUID getAccountId();
        String getPasswordHash();
        boolean getEmailVerified();
        long getSessionVersion();
    }
}
