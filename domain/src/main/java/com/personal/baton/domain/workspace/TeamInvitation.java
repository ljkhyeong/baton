package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "team_invitations")
public class TeamInvitation {
    @Id @Column(columnDefinition = "binary(16)")
    private UUID id;
    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;
    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;
    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private TeamPermission permission;
    @Column(name = "created_by", nullable = false, columnDefinition = "binary(16)")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "accepted_by", columnDefinition = "binary(16)")
    private UUID acceptedBy;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    protected TeamInvitation() {}
    public static TeamInvitation create(UUID teamId, UUID memberId, String tokenHash, TeamPermission permission,
            UUID createdBy, Instant createdAt, Instant expiresAt) {
        TeamInvitation result = new TeamInvitation();
        result.id = UUID.randomUUID(); result.teamId = Objects.requireNonNull(teamId);
        result.memberId = Objects.requireNonNull(memberId);
        result.tokenHash = DomainAssertions.requiredSha256Hex(tokenHash, "초대 토큰 해시");
        result.permission = Objects.requireNonNull(permission); result.createdBy = Objects.requireNonNull(createdBy);
        result.createdAt = Objects.requireNonNull(createdAt); result.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) throw new DomainValidationException("초대 만료 시각은 생성 이후여야 합니다");
        return result;
    }
    public boolean isPending(Instant now) { return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt); }
    public void accept(UUID accountId, Instant now) {
        if (!isPending(now)) throw new DomainValidationException("수락할 수 없는 초대입니다");
        acceptedBy = Objects.requireNonNull(accountId); acceptedAt = Objects.requireNonNull(now);
    }
    public void revoke(Instant now) { if (acceptedAt == null && revokedAt == null) revokedAt = Objects.requireNonNull(now); }
    public UUID getId() { return id; }
    public UUID getTeamId() { return teamId; }
    public UUID getMemberId() { return memberId; }
    public TeamPermission getPermission() { return permission; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public UUID getAcceptedBy() { return acceptedBy; }
    public Instant getRevokedAt() { return revokedAt; }
}
