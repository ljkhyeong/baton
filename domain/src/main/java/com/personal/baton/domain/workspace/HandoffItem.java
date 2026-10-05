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
public class HandoffItem extends ArchivableRecord {

    private static final String ARCHIVED_MESSAGE = "보관된 인수인계 항목은 수정할 수 없습니다";

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected HandoffItem() {
    }

    private HandoffItem(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "인수인계 항목 식별자는 필수입니다");
        update(roleId, label, category);
        this.completed = completed;
        this.createdAt = Objects.requireNonNull(createdAt, "인수인계 항목 생성 시각은 필수입니다");
    }

    public static HandoffItem create(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt
    ) {
        return new HandoffItem(id, roleId, label, category, completed, createdAt);
    }

    public void update(UUID roleId, String label, HandoffCategory category) {
        requireActive(ARCHIVED_MESSAGE);
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

    // 완료 상태가 바뀌었으면 true를 돌려준다.
    public boolean updateCompletion(boolean completed) {
        requireActive(ARCHIVED_MESSAGE);
        boolean changed = this.completed != completed;
        this.completed = completed;
        return changed;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
