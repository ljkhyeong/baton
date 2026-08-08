package com.personal.baton.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "email_verification_challenges",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_email_verification_challenges_identity",
                        columnNames = "identity_id"
                ),
                @UniqueConstraint(
                        name = "uk_email_verification_challenges_token_hash",
                        columnNames = "token_hash"
                )
        }
)
public class EmailVerificationChallenge {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "identity_id", nullable = false, columnDefinition = "binary(16)")
    private UUID identityId;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected EmailVerificationChallenge() {
    }

    private EmailVerificationChallenge(
            UUID id,
            UUID identityId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "이메일 인증 도전 식별자는 필수입니다");
        this.identityId = Objects.requireNonNull(identityId, "로컬 신원 식별자는 필수입니다");
        this.tokenHash = IdentityAssertions.requiredSha256Hex(tokenHash, "이메일 인증 토큰 해시");
        this.createdAt = IdentityAssertions.requiredInstant(createdAt, "이메일 인증 요청 시각");
        this.expiresAt = IdentityAssertions.requiredInstant(expiresAt, "이메일 인증 만료 시각");
        if (!this.expiresAt.isAfter(this.createdAt)) {
            throw new IdentityValidationException(
                    "이메일 인증 만료 시각은 요청 시각보다 늦어야 합니다"
            );
        }
    }

    public static EmailVerificationChallenge create(
            UUID id,
            UUID identityId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        return new EmailVerificationChallenge(id, identityId, tokenHash, createdAt, expiresAt);
    }

    public boolean consume(Instant now) {
        Instant consumedAtCandidate = IdentityAssertions.requiredInstant(now, "이메일 인증 시각");
        if (consumedAt != null
                || consumedAtCandidate.isBefore(createdAt)
                || !consumedAtCandidate.isBefore(expiresAt)) {
            return false;
        }
        this.consumedAt = consumedAtCandidate;
        return true;
    }

    public void reissue(String tokenHash, Instant createdAt, Instant expiresAt) {
        if (consumedAt != null) {
            throw new IdentityValidationException(
                    "소비한 이메일 인증 요청은 다시 발급할 수 없습니다"
            );
        }
        String normalizedTokenHash = IdentityAssertions.requiredSha256Hex(
                tokenHash,
                "이메일 인증 토큰 해시"
        );
        Instant normalizedCreatedAt = IdentityAssertions.requiredInstant(
                createdAt,
                "이메일 인증 재발급 시각"
        );
        Instant normalizedExpiresAt = IdentityAssertions.requiredInstant(
                expiresAt,
                "이메일 인증 만료 시각"
        );
        if (!normalizedExpiresAt.isAfter(normalizedCreatedAt)) {
            throw new IdentityValidationException(
                    "이메일 인증 만료 시각은 재발급 시각보다 늦어야 합니다"
            );
        }
        this.tokenHash = normalizedTokenHash;
        this.createdAt = normalizedCreatedAt;
        this.expiresAt = normalizedExpiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
