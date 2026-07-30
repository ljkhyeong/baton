package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.MemberInvitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberInvitationJpaRepository
        extends JpaRepository<MemberInvitation, UUID> {

    Optional<MemberInvitation> findByIdempotencyKeyHash(String idempotencyKeyHash);

    Optional<MemberInvitation> findByTokenHash(String tokenHash);

    @Query("""
            select
              invitation.id as invitationId,
              invitation.teamId as teamId,
              invitation.memberId as memberId,
              invitation.issuedByAccountId as issuedByAccountId,
              invitation.expiresAt as expiresAt,
              invitation.revokedAt as revokedAt,
              invitation.consumedAt as consumedAt
            from MemberInvitation invitation
            where invitation.tokenHash = :tokenHash
            """)
    Optional<InvitationObservationView> findObservationByTokenHash(
            @Param("tokenHash") String tokenHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from MemberInvitation invitation
            where invitation.idempotencyKeyHash = :idempotencyKeyHash
            """)
    Optional<MemberInvitation> findByIdempotencyKeyHashForUpdate(
            @Param("idempotencyKeyHash") String idempotencyKeyHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from MemberInvitation invitation
            where invitation.tokenHash = :tokenHash
            """)
    Optional<MemberInvitation> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from MemberInvitation invitation
            where invitation.teamId = :teamId
              and invitation.id = :invitationId
            """)
    Optional<MemberInvitation> findByTeamIdAndIdForUpdate(
            @Param("teamId") UUID teamId,
            @Param("invitationId") UUID invitationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from MemberInvitation invitation
            where invitation.teamId = :teamId
              and invitation.memberId = :memberId
              and invitation.consumedAt is null
              and invitation.revokedAt is null
              and invitation.expiresAt > :now
            order by invitation.issuedAt desc
            """)
    List<MemberInvitation> findOpenByTeamIdAndMemberIdForUpdate(
            @Param("teamId") UUID teamId,
            @Param("memberId") UUID memberId,
            @Param("now") Instant now
    );

    @Query("""
            select invitation
            from MemberInvitation invitation
            where invitation.teamId = :teamId
              and invitation.consumedAt is null
              and invitation.revokedAt is null
              and invitation.expiresAt > :now
            order by invitation.issuedAt desc, invitation.id
            """)
    List<MemberInvitation> findAllOpenByTeamId(
            @Param("teamId") UUID teamId,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO member_invitations (
                id,
                team_id,
                member_id,
                issued_by_account_id,
                idempotency_key_hash,
                token_hash,
                issued_at,
                expires_at,
                version
            ) VALUES (
                UUID_TO_BIN(:invitationId),
                UUID_TO_BIN(:teamId),
                UUID_TO_BIN(:memberId),
                UUID_TO_BIN(:issuedByAccountId),
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
            @Param("issuedByAccountId") String issuedByAccountId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("tokenHash") String tokenHash,
            @Param("issuedAt") Instant issuedAt,
            @Param("expiresAt") Instant expiresAt
    );

    interface InvitationObservationView {

        UUID getInvitationId();

        UUID getTeamId();

        UUID getMemberId();

        UUID getIssuedByAccountId();

        Instant getExpiresAt();

        Instant getRevokedAt();

        Instant getConsumedAt();
    }
}
