package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    protected HandoffItem() {
    }

    private HandoffItem(UUID id, UUID roleId, String label, HandoffCategory category, boolean completed) {
        this.id = Objects.requireNonNull(id, "인수인계 항목 식별자는 필수입니다");
        this.roleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        this.label = DomainAssertions.requiredText(label, "인수인계 항목", 500);
        this.category = Objects.requireNonNull(category, "인수인계 분류는 필수입니다");
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

    public void updateCompletion(boolean completed) {
        this.completed = completed;
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
}
