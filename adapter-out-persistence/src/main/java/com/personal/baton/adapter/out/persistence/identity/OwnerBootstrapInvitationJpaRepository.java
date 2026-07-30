package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OwnerBootstrapInvitationJpaRepository
        extends JpaRepository<OwnerBootstrapInvitation, UUID> {

    Optional<OwnerBootstrapInvitation> findByIdempotencyKeyHash(String idempotencyKeyHash);

    Optional<OwnerBootstrapInvitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from OwnerBootstrapInvitation invitation
            where invitation.idempotencyKeyHash = :idempotencyKeyHash
            """)
    Optional<OwnerBootstrapInvitation> findByIdempotencyKeyHashForUpdate(
            @Param("idempotencyKeyHash") String idempotencyKeyHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from OwnerBootstrapInvitation invitation
            where invitation.tokenHash = :tokenHash
            """)
    Optional<OwnerBootstrapInvitation> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from OwnerBootstrapInvitation invitation
            where invitation.id = :invitationId
            """)
    Optional<OwnerBootstrapInvitation> findByIdForUpdate(
            @Param("invitationId") UUID invitationId
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO owner_bootstrap_invitations (
                id,
                team_id,
                member_id,
                idempotency_key_hash,
                token_hash,
                issued_at,
                expires_at,
                version
            ) VALUES (
                UUID_TO_BIN(:invitationId),
                UUID_TO_BIN(:teamId),
                UUID_TO_BIN(:memberId),
                :idempotencyKeyHash,
                :tokenHash,
                :issuedAt,
                :expiresAt,
                0
            )
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("invitationId") String invitationId,
            @Param("teamId") String teamId,
            @Param("memberId") String memberId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("tokenHash") String tokenHash,
            @Param("issuedAt") Instant issuedAt,
            @Param("expiresAt") Instant expiresAt
    );
}
