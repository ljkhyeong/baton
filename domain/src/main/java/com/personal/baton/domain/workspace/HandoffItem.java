package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "handoff_items")
public class HandoffItem {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "role_id", nullable = false, columnDefinition = "binary(16)")
    private UUID roleId;

    @Column(nullable = false, length = 500)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private HandoffCategory category;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected HandoffItem() {
    }

    private HandoffItem(UUID id, UUID roleId, String label, HandoffCategory category, boolean completed) {
        this.id = Objects.requireNonNull(id, "인수인계 항목 식별자는 필수입니다");
        update(roleId, label, category);
        this.completed = completed;
    }

    public static HandoffItem create(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed
    ) {
        return new HandoffItem(id, roleId, label, category, completed);
    }

    public void update(UUID roleId, String label, HandoffCategory category) {
        requireActive();
        UUID normalizedRoleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        String normalizedLabel = DomainAssertions.requiredText(label, "인수인계 항목", 500);
        HandoffCategory normalizedCategory = Objects.requireNonNull(
                category,
                "인수인계 분류는 필수입니다"
        );

        this.roleId = normalizedRoleId;
        this.label = normalizedLabel;
        this.category = normalizedCategory;
    }

    public void updateCompletion(boolean completed) {
        requireActive();
        this.completed = completed;
    }

    public void updateArchive(boolean archived, Instant archivedAt) {
        if (archived) {
            if (this.archivedAt == null) {
                this.archivedAt = Objects.requireNonNull(archivedAt, "인수인계 항목 보관 시각은 필수입니다");
            }
            return;
        }
        this.archivedAt = null;
    }

    private void requireActive() {
        if (archivedAt != null) {
            throw new DomainValidationException("보관된 인수인계 항목은 수정할 수 없습니다");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public String getLabel() {
        return label;
    }

    public HandoffCategory getCategory() {
        return category;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
