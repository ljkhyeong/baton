package com.personal.baton.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "member_identity_bindings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_identity_bindings_team_account",
                columnNames = {"team_id", "user_account_id"}
        )
)
public class MemberIdentityBinding {

    @Id
    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "user_account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID userAccountId;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberIdentityRole role;

    @Version
    @Column(nullable = false)
    private Long version;

    protected MemberIdentityBinding() {
    }

    private MemberIdentityBinding(
            UUID memberId,
            UUID teamId,
            UUID userAccountId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
        this.memberId = Objects.requireNonNull(memberId, "구성원 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.userAccountId = Objects.requireNonNull(
                userAccountId,
                "사용자 계정 식별자는 필수입니다"
        );
        this.boundAt = Objects.requireNonNull(boundAt, "사용자 결속 시각은 필수입니다");
        this.role = Objects.requireNonNull(role, "구성원 신원 역할은 필수입니다");
    }

    public static MemberIdentityBinding bind(
            UUID memberId,
            UUID teamId,
            UUID userAccountId,
            Instant boundAt
    ) {
        return bind(memberId, teamId, userAccountId, boundAt, MemberIdentityRole.MEMBER);
    }

    public static MemberIdentityBinding bind(
            UUID memberId,
            UUID teamId,
            UUID userAccountId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
        return new MemberIdentityBinding(memberId, teamId, userAccountId, boundAt, role);
    }

    public boolean belongsTo(UUID userAccountId) {
        return this.userAccountId.equals(userAccountId);
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserAccountId() {
        return userAccountId;
    }

    public Instant getBoundAt() {
        return boundAt;
    }

    public MemberIdentityRole getRole() {
        return role;
    }

    public boolean isOwner() {
        return role == MemberIdentityRole.OWNER;
    }

    public void grantOwner() {
        role = MemberIdentityRole.OWNER;
    }
}
