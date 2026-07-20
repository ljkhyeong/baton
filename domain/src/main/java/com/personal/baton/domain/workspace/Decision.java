package com.personal.baton.domain.workspace;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "decisions")
public class Decision {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false, length = 2000)
    private String alternative;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "author_member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID authorMemberId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "decision_roles",
            joinColumns = @JoinColumn(name = "decision_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_decision_roles_decision_role",
                    columnNames = {"decision_id", "role_id"}
            )
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "role_id", nullable = false, columnDefinition = "binary(16)")
    private List<UUID> roleIds = new ArrayList<>();

    protected Decision() {
    }

    private Decision(
            UUID id,
            UUID seasonId,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
        this.id = Objects.requireNonNull(id, "결정 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.title = DomainAssertions.requiredText(title, "결정 제목", 200);
        this.reason = DomainAssertions.requiredText(reason, "결정 이유", 2000);
        this.alternative = DomainAssertions.optionalTextOrEmpty(alternative, "검토 대안", 2000);
        this.createdAt = Objects.requireNonNull(createdAt, "결정 생성 시각은 필수입니다");
        this.authorMemberId = Objects.requireNonNull(authorMemberId, "작성자 구성원 식별자는 필수입니다");
        if (roleIds == null || roleIds.isEmpty()) {
            throw new DomainValidationException("관련 역할은 한 개 이상이어야 합니다");
        }
        for (UUID roleId : roleIds) {
            this.roleIds.add(Objects.requireNonNull(roleId, "관련 역할 식별자는 비어 있을 수 없습니다"));
        }
    }

    public static Decision create(
            UUID id,
            UUID seasonId,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
        return new Decision(id, seasonId, title, reason, alternative, createdAt, authorMemberId, roleIds);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public String getTitle() {
        return title;
    }

    public String getReason() {
        return reason;
    }

    public String getAlternative() {
        return alternative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getAuthorMemberId() {
        return authorMemberId;
    }

    public List<UUID> getRoleIds() {
        return Collections.unmodifiableList(roleIds);
    }
}
