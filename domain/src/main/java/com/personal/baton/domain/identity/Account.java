package com.personal.baton.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Account() {
    }

    private Account(UUID id, String displayName, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "계정 식별자는 필수입니다");
        this.displayName = IdentityAssertions.requiredText(displayName, "표시 이름", 100);
        this.createdAt = Objects.requireNonNull(createdAt, "계정 생성 시각은(는) 필수입니다");
        this.updatedAt = this.createdAt;
    }

    public static Account create(UUID id, String displayName, Instant createdAt) {
        return new Account(id, displayName, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getSessionVersion() {
        return sessionVersion;
    }

    public boolean isActive() { return deactivatedAt == null; }

    public void deactivate(Instant changedAt) {
        if (deactivatedAt != null) return;
        invalidateSessions(changedAt);
        deactivatedAt = changedAt;
    }

    public void invalidateSessions(Instant changedAt) {
        this.updatedAt = Objects.requireNonNull(changedAt, "세션 무효화 시각은 필수입니다");
        this.sessionVersion++;
    }

}
