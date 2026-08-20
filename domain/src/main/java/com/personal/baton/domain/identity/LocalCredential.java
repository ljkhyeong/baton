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
@Table(name = "local_credentials")
public class LocalCredential {

    @Id
    @Column(name = "identity_id", nullable = false, columnDefinition = "binary(16)")
    private UUID identityId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected LocalCredential() {
    }

    private LocalCredential(UUID identityId, String passwordHash, Instant createdAt) {
        this.identityId = Objects.requireNonNull(identityId, "로컬 신원 식별자는 필수입니다");
        this.passwordHash = IdentityAssertions.requiredOpaqueHash(passwordHash);
        this.createdAt = IdentityAssertions.requiredInstant(createdAt, "자격 증명 생성 시각");
        this.updatedAt = this.createdAt;
    }

    public static LocalCredential create(
            UUID identityId,
            String passwordHash,
            Instant createdAt
    ) {
        return new LocalCredential(identityId, passwordHash, createdAt);
    }

    public void replacePasswordHash(String passwordHash, Instant updatedAt) {
        String requiredPasswordHash = IdentityAssertions.requiredOpaqueHash(passwordHash);
        Instant requiredUpdatedAt = IdentityAssertions.requiredInstant(
                updatedAt,
                "자격 증명 수정 시각"
        );
        if (requiredUpdatedAt.isBefore(this.updatedAt)) {
            throw new IdentityValidationException(
                    "자격 증명 수정 시각은 이전 수정 시각보다 빠를 수 없습니다"
            );
        }
        this.passwordHash = requiredPasswordHash;
        this.updatedAt = requiredUpdatedAt;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
