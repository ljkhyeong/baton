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
        name = "owner_bootstrap_invitations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_owner_bootstrap_invitations_idempotency",
                        columnNames = "idempotency_key_hash"
                ),
                @UniqueConstraint(
                        name = "uk_owner_bootstrap_invitations_token",
                        columnNames = "token_hash"
                )
        }
)
public class OwnerBootstrapInvitation {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;

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

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_account_id", columnDefinition = "binary(16)")
    private UUID consumedByAccountId;

    @Version
    @Column(nullable = false)
    private Long version;

    protected OwnerBootstrapInvitation() {
    }

    private OwnerBootstrapInvitation(
            UUID id,
            UUID teamId,
            UUID memberId,
            String idempotencyKeyHash,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "bootstrap 초대 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "bootstrap 초대 팀은 필수입니다");
        this.memberId = Objects.requireNonNull(memberId, "bootstrap 초대 구성원은 필수입니다");
        this.idempotencyKeyHash = requireHash(idempotencyKeyHash, "멱등 키 해시");
        this.tokenHash = requireHash(tokenHash, "초대 토큰 해시");
        this.issuedAt = Objects.requireNonNull(issuedAt, "bootstrap 초대 발급 시각은 필수입니다");
        this.expiresAt = Objects.requireNonNull(expiresAt, "bootstrap 초대 만료 시각은 필수입니다");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("bootstrap 초대 만료 시각은 발급 시각보다 뒤여야 합니다");
        }
    }

    public static OwnerBootstrapInvitation issue(
            UUID id,
            UUID teamId,
            UUID memberId,
            String idempotencyKeyHash,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return new OwnerBootstrapInvitation(
                id,
                teamId,
                memberId,
                idempotencyKeyHash,
                tokenHash,
                issuedAt,
                expiresAt
        );
    }

    public boolean matchesCreation(
            UUID teamId,
            UUID memberId,
            String idempotencyKeyHash,
            String tokenHash
    ) {
        return this.teamId.equals(teamId)
                && this.memberId.equals(memberId)
                && this.idempotencyKeyHash.equals(idempotencyKeyHash)
                && this.tokenHash.equals(tokenHash);
    }

    public Acceptance consume(UUID accountId, Instant now) {
        Objects.requireNonNull(accountId, "bootstrap 초대 소비 계정은 필수입니다");
        Objects.requireNonNull(now, "bootstrap 초대 소비 시각은 필수입니다");
        if (consumedAt != null) {
            if (accountId.equals(consumedByAccountId)) {
                return Acceptance.REPLAY;
            }
            throw new OwnerBootstrapInvitationStateException(
                    OwnerBootstrapInvitationStateException.Reason.USED
            );
        }
        assertAvailable(now);
        consumedAt = now;
        consumedByAccountId = accountId;
        return Acceptance.CONSUMED;
    }

    public void revoke(Instant now) {
        Objects.requireNonNull(now, "bootstrap 초대 폐기 시각은 필수입니다");
        if (consumedAt != null) {
            throw new OwnerBootstrapInvitationStateException(
                    OwnerBootstrapInvitationStateException.Reason.USED
            );
        }
        if (revokedAt != null) {
            return;
        }
        if (!now.isBefore(expiresAt)) {
            throw new OwnerBootstrapInvitationStateException(
                    OwnerBootstrapInvitationStateException.Reason.EXPIRED
            );
        }
        revokedAt = now;
    }

    public void assertAvailable(Instant now) {
        Objects.requireNonNull(now, "bootstrap 초대 확인 시각은 필수입니다");
        if (revokedAt != null) {
            throw new OwnerBootstrapInvitationStateException(
                    OwnerBootstrapInvitationStateException.Reason.REVOKED
            );
        }
        if (!now.isBefore(expiresAt)) {
            throw new OwnerBootstrapInvitationStateException(
                    OwnerBootstrapInvitationStateException.Reason.EXPIRED
            );
        }
    }

    public boolean wasConsumedBy(UUID accountId) {
        return consumedAt != null && consumedByAccountId.equals(accountId);
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

    public enum Acceptance {
        CONSUMED,
        REPLAY
    }
}
