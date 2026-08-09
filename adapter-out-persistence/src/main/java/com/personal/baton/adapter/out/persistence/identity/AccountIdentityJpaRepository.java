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
import org.springframework.data.repository.query.Param;

public interface AccountIdentityJpaRepository extends JpaRepository<AccountIdentity, UUID> {

    Optional<AccountIdentity> findByProviderAndProviderSubject(
            IdentityProvider provider,
            String providerSubject
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select identity
            from AccountIdentity identity
            where identity.provider = :provider
              and identity.providerSubject = :providerSubject
            """)
    Optional<AccountIdentity> findByProviderAndProviderSubjectForUpdate(
            @Param("provider") IdentityProvider provider,
            @Param("providerSubject") String providerSubject
    );

    List<AccountIdentity> findAllByAccountIdOrderByProviderAsc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select identity from AccountIdentity identity where identity.id = :identityId")
    Optional<AccountIdentity> findByIdForUpdate(@Param("identityId") UUID identityId);
}
