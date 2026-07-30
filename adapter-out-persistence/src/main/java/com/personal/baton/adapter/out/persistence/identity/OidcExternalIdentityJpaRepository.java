package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.OidcExternalIdentity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OidcExternalIdentityJpaRepository
        extends JpaRepository<OidcExternalIdentity, UUID> {

    Optional<OidcExternalIdentity> findByIdentityKeyHash(String identityKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select identity
            from OidcExternalIdentity identity
            where identity.identityKeyHash = :identityKeyHash
            """)
    Optional<OidcExternalIdentity> findByIdentityKeyHashForUpdate(
            @Param("identityKeyHash") String identityKeyHash
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO oidc_external_identities (
                id,
                identity_key_hash,
                issuer,
                subject,
                user_account_id,
                linked_at,
                version
            ) VALUES (
                UUID_TO_BIN(:identityId),
                :identityKeyHash,
                :issuer,
                :subject,
                UUID_TO_BIN(:accountId),
                :linkedAt,
                0
            )
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("identityId") String identityId,
            @Param("identityKeyHash") String identityKeyHash,
            @Param("issuer") String issuer,
            @Param("subject") String subject,
            @Param("accountId") String accountId,
            @Param("linkedAt") Instant linkedAt
    );
}
