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
        name = "access_key_change_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_access_key_change_history_team_hash",
                columnNames = {"team_id", "idempotency_hash"}
        )
)
public class AccessKeyChangeHistory {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "idempotency_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String idempotencyHash;

    protected AccessKeyChangeHistory() {
    }

    private AccessKeyChangeHistory(UUID id, UUID teamId, String idempotencyHash) {
        this.id = Objects.requireNonNull(id, "접근 키 변경 이력 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.idempotencyHash = DomainAssertions.requiredSha256Hex(
                idempotencyHash,
                "접근 키 변경 멱등 키 해시"
        );
    }

    public static AccessKeyChangeHistory create(UUID id, UUID teamId, String idempotencyHash) {
        return new AccessKeyChangeHistory(id, teamId, idempotencyHash);
    }

}
