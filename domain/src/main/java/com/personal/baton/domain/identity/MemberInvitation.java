package com.personal.baton.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(
        name = "member_invitations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_member_invitations_idempotency",
                        columnNames = "idempotency_key_hash"
                ),
                @UniqueConstraint(
                        name = "uk_member_invitations_token",
                        columnNames = "token_hash"
                )
        }
)
public class MemberInvitation {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;

    @Column(name = "issued_by_account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID issuedByAccountId;

    @Column(
            name = "idempotency_key_hash",
            nullable = false,
            length = 64,
            columnDefinition = "char(64)"
    )
    private String idempotencyKeyHash;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_account_id", columnDefinition = "binary(16)")
    private UUID revokedByAccountId;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_account_id", columnDefinition = "binary(16)")
    private UUID consumedByAccountId;

    @Version
    @Column(nullable = false)
    private Long version;

    protected MemberInvitation() {
    }

    private MemberInvitation(
            UUID id,
            UUID teamId,
            UUID memberId,
            UUID issuedByAccountId,
            String idempotencyKeyHash,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "구성원 초대 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "구성원 초대 팀은 필수입니다");
        this.memberId = Objects.requireNonNull(memberId, "구성원 초대 대상은 필수입니다");
        this.issuedByAccountId = Objects.requireNonNull(
                issuedByAccountId,
                "구성원 초대 발급 계정은 필수입니다"
        );
        this.idempotencyKeyHash = requireHash(idempotencyKeyHash, "멱등 키 해시");
        this.tokenHash = requireHash(tokenHash, "초대 토큰 해시");
        this.issuedAt = Objects.requireNonNull(issuedAt, "구성원 초대 발급 시각은 필수입니다");
        this.expiresAt = Objects.requireNonNull(expiresAt, "구성원 초대 만료 시각은 필수입니다");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("구성원 초대 만료 시각은 발급 시각보다 뒤여야 합니다");
        }
    }

    public static MemberInvitation issue(
            UUID id,
            UUID teamId,
            UUID memberId,
            UUID issuedByAccountId,
            String idempotencyKeyHash,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return new MemberInvitation(
                id,
                teamId,
                memberId,
                issuedByAccountId,
                idempotencyKeyHash,
                tokenHash,
                issuedAt,
                expiresAt
        );
    }

    public boolean matchesCreation(
            UUID teamId,
            UUID memberId,
            UUID issuedByAccountId,
            String idempotencyKeyHash,
            String tokenHash
    ) {
        return this.teamId.equals(teamId)
                && this.memberId.equals(memberId)
                && this.issuedByAccountId.equals(issuedByAccountId)
                && this.idempotencyKeyHash.equals(idempotencyKeyHash)
                && this.tokenHash.equals(tokenHash);
    }

    public Availability inspect(UUID accountId, Instant now) {
        Objects.requireNonNull(accountId, "구성원 초대 확인 계정은 필수입니다");
        Objects.requireNonNull(now, "구성원 초대 확인 시각은 필수입니다");
        if (consumedAt != null) {
            if (accountId.equals(consumedByAccountId)) {
                return Availability.REPLAY;
            }
            throw new MemberInvitationStateException(MemberInvitationStateException.Reason.USED);
        }
        assertAvailable(now);
        return Availability.AVAILABLE;
    }

    public Acceptance consume(UUID accountId, Instant now) {
        Availability availability = inspect(accountId, now);
        if (availability == Availability.REPLAY) {
            return Acceptance.REPLAY;
        }
        consumedAt = now;
        consumedByAccountId = accountId;
        return Acceptance.CONSUMED;
    }

    public Revocation revoke(UUID accountId, Instant now) {
        Objects.requireNonNull(accountId, "구성원 초대 폐기 계정은 필수입니다");
        Objects.requireNonNull(now, "구성원 초대 폐기 시각은 필수입니다");
        if (consumedAt != null) {
            throw new MemberInvitationStateException(MemberInvitationStateException.Reason.USED);
        }
        if (revokedAt != null) {
            return Revocation.REPLAY;
        }
        if (!now.isBefore(expiresAt)) {
            throw new MemberInvitationStateException(MemberInvitationStateException.Reason.EXPIRED);
        }
        revokedAt = now;
        revokedByAccountId = accountId;
        return Revocation.REVOKED;
    }

    public boolean isOpenAt(Instant now) {
        return consumedAt == null
                && revokedAt == null
                && now.isBefore(expiresAt);
    }

    private void assertAvailable(Instant now) {
        if (revokedAt != null) {
            throw new MemberInvitationStateException(MemberInvitationStateException.Reason.REVOKED);
        }
        if (!now.isBefore(expiresAt)) {
            throw new MemberInvitationStateException(MemberInvitationStateException.Reason.EXPIRED);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getIssuedByAccountId() {
        return issuedByAccountId;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedByAccountId() {
        return revokedByAccountId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public UUID getConsumedByAccountId() {
        return consumedByAccountId;
    }

    private static String requireHash(String value, String field) {
        Objects.requireNonNull(value, field + "은 필수입니다");
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "은 SHA-256 hex 형식이어야 합니다");
        }
        return value;
    }

    public enum Availability {
        AVAILABLE,
        REPLAY
    }

    public enum Acceptance {
        CONSUMED,
        REPLAY
    }

    public enum Revocation {
        REVOKED,
        REPLAY
    }
}
