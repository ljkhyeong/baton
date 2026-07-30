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
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected UserAccount() {
    }

    private UserAccount(UUID id, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "사용자 계정 식별자는 필수입니다");
        this.createdAt = Objects.requireNonNull(createdAt, "사용자 계정 생성 시각은 필수입니다");
    }

    public static UserAccount create(UUID id, Instant createdAt) {
        return new UserAccount(id, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
