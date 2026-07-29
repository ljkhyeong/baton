package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_members_team_name",
                columnNames = {"team_id", "name"}
        )
)
public class Member {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Member() {
    }

    private Member(UUID id, UUID teamId, String name) {
        this.id = Objects.requireNonNull(id, "구성원 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        rename(name);
    }

    public static Member create(UUID id, UUID teamId, String name) {
        return new Member(id, teamId, name);
    }

    public static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "구성원 이름", 100);
    }

    public void rename(String name) {
        this.name = normalizeName(name);
    }

    public void updateDeactivation(boolean deactivated, Instant deactivatedAt) {
        if (deactivated) {
            if (this.deactivatedAt == null) {
                this.deactivatedAt = Objects.requireNonNull(
                        deactivatedAt,
                        "구성원 비활성 시각은 필수입니다"
                );
            }
            return;
        }
        this.deactivatedAt = null;
    }

    public boolean isActive() {
        return deactivatedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }
}
