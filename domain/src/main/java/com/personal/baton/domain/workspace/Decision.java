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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

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
        this.createdAt = Objects.requireNonNull(createdAt, "결정 생성 시각은 필수입니다");
        update(title, reason, alternative, authorMemberId, roleIds);
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

    public void update(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
        requireActive();
        String normalizedTitle = DomainAssertions.requiredText(title, "결정 제목", 200);
        String normalizedReason = DomainAssertions.requiredText(reason, "결정 이유", 2000);
        String normalizedAlternative = DomainAssertions.optionalTextOrEmpty(alternative, "검토 대안", 2000);
        UUID normalizedAuthorMemberId = Objects.requireNonNull(
                authorMemberId,
                "작성자 구성원 식별자는 필수입니다"
        );
        List<UUID> normalizedRoleIds = normalizeRoleIds(roleIds);

        this.title = normalizedTitle;
        this.reason = normalizedReason;
        this.alternative = normalizedAlternative;
        this.authorMemberId = normalizedAuthorMemberId;
        this.roleIds.clear();
        this.roleIds.addAll(normalizedRoleIds);
    }

    public void updateArchive(boolean archived, Instant archivedAt) {
        if (archived) {
            if (this.archivedAt == null) {
                this.archivedAt = Objects.requireNonNull(archivedAt, "결정 보관 시각은 필수입니다");
            }
            return;
        }
        this.archivedAt = null;
    }

    private static List<UUID> normalizeRoleIds(List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new DomainValidationException("관련 역할은 한 개 이상이어야 합니다");
        }
        List<UUID> normalized = new ArrayList<>();
        Set<UUID> uniqueRoleIds = new HashSet<>();
        for (UUID roleId : roleIds) {
            UUID normalizedRoleId = Objects.requireNonNull(
                    roleId,
                    "관련 역할 식별자는 비어 있을 수 없습니다"
            );
            if (!uniqueRoleIds.add(normalizedRoleId)) {
                throw new DomainValidationException("관련 역할은 중복될 수 없습니다");
            }
            normalized.add(normalizedRoleId);
        }
        return normalized;
    }

    private void requireActive() {
        if (archivedAt != null) {
            throw new DomainValidationException("보관된 결정은 수정할 수 없습니다");
        }
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

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public List<UUID> getRoleIds() {
        return Collections.unmodifiableList(roleIds);
    }
}
