package com.personal.baton.domain.roundauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "account_team_memberships",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_account_team_memberships_account_team",
                    columnNames = {"account_id", "team_id"}
            ),
            @UniqueConstraint(
                    name = "uk_account_team_memberships_member",
                    columnNames = "member_id"
            )
        }
)
public class AccountTeamMembership {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID accountId;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    protected AccountTeamMembership() {
    }

    private AccountTeamMembership(
            UUID id,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant claimedAt
    ) {
        this.id = Objects.requireNonNull(id, "멤버십 식별자는 필수입니다");
        this.accountId = Objects.requireNonNull(accountId, "계정 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.memberId = Objects.requireNonNull(memberId, "구성원 식별자는 필수입니다");
        this.claimedAt = Objects.requireNonNull(claimedAt, "멤버십 연결 시각은 필수입니다");
    }

    public static AccountTeamMembership create(
            UUID id,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant claimedAt
    ) {
        return new AccountTeamMembership(id, accountId, teamId, memberId, claimedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }
}
