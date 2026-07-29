package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

    protected Member() {
    }

    private Member(UUID id, UUID teamId, String name) {
        this.id = Objects.requireNonNull(id, "구성원 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "구성원 이름", 100);
    }

    public static Member create(UUID id, UUID teamId, String name) {
        return new Member(id, teamId, name);
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
}
