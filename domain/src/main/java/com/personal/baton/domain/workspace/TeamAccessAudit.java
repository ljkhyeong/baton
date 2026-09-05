package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_access_audit")
public class TeamAccessAudit {
    @Id @Column(columnDefinition = "binary(16)") private UUID id;
    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)") private UUID teamId;
    @Column(name = "actor_account_id", nullable = false, columnDefinition = "binary(16)") private UUID actorAccountId;
    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)") private UUID memberId;
    @Column(name = "action", nullable = false, length = 30) private String action;
    @Enumerated(EnumType.STRING) @Column(name = "previous_permission", length = 16) private TeamPermission previousPermission;
    @Enumerated(EnumType.STRING) @Column(name = "permission", length = 16) private TeamPermission permission;
    @Column(name = "changed_at", nullable = false) private Instant changedAt;
    protected TeamAccessAudit() {}
    public static TeamAccessAudit create(UUID teamId, UUID actorAccountId, UUID memberId, String action,
            TeamPermission previousPermission, TeamPermission permission, Instant changedAt) {
        TeamAccessAudit audit = new TeamAccessAudit(); audit.id = UUID.randomUUID(); audit.teamId = teamId;
        audit.actorAccountId = actorAccountId; audit.memberId = memberId; audit.action = action;
        audit.previousPermission = previousPermission; audit.permission = permission; audit.changedAt = changedAt;
        return audit;
    }
    public UUID getId() { return id; }
    public UUID getActorAccountId() { return actorAccountId; }
    public UUID getMemberId() { return memberId; }
    public String getAction() { return action; }
    public TeamPermission getPreviousPermission() { return previousPermission; }
    public TeamPermission getPermission() { return permission; }
    public Instant getChangedAt() { return changedAt; }
}
