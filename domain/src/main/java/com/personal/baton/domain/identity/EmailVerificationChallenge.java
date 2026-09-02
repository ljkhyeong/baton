package com.personal.baton.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmailChallengePurpose purpose = EmailChallengePurpose.REGISTRATION;

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
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "이메일 인증 요청 시각은(는) 필수입니다"
        );
        this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "이메일 인증 만료 시각은(는) 필수입니다"
        );
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
        Instant checkedAt = Objects.requireNonNull(
                now,
                "이메일 인증 시각은(는) 필수입니다"
        );
        if (!isPendingAtValidated(checkedAt)) {
            return false;
        }
        this.consumedAt = checkedAt;
        return true;
    }

    public static EmailVerificationChallenge createForPasswordReset(
            UUID id, UUID identityId, String tokenHash, Instant createdAt, Instant expiresAt
    ) {
        EmailVerificationChallenge challenge =
                new EmailVerificationChallenge(id, identityId, tokenHash, createdAt, expiresAt);
        challenge.purpose = EmailChallengePurpose.PASSWORD_RESET;
        return challenge;
    }

    public boolean isPendingAt(Instant now) {
        Instant checkedAt = Objects.requireNonNull(
                now,
                "이메일 인증 확인 시각은(는) 필수입니다"
        );
        return isPendingAtValidated(checkedAt);
    }

    private boolean isPendingAtValidated(Instant checkedAt) {
        return consumedAt == null
                && !checkedAt.isBefore(createdAt)
                && checkedAt.isBefore(expiresAt);
    }

    public void reissue(String tokenHash, Instant createdAt, Instant expiresAt) {
        if (consumedAt != null || purpose != EmailChallengePurpose.REGISTRATION) {
            throw new IdentityValidationException(
                    "소비한 이메일 인증 요청은 다시 발급할 수 없습니다"
            );
        }
        replaceToken(tokenHash, createdAt, expiresAt);
    }

    public void reissueForPasswordReset(String tokenHash, Instant createdAt, Instant expiresAt) {
        replaceToken(tokenHash, createdAt, expiresAt);
        this.purpose = EmailChallengePurpose.PASSWORD_RESET;
        this.consumedAt = null;
    }

    private void replaceToken(String tokenHash, Instant createdAt, Instant expiresAt) {
        String normalizedTokenHash = IdentityAssertions.requiredSha256Hex(
                tokenHash,
                "이메일 인증 토큰 해시"
        );
        Instant normalizedCreatedAt = Objects.requireNonNull(
                createdAt,
                "이메일 인증 재발급 시각은(는) 필수입니다"
        );
        Instant normalizedExpiresAt = Objects.requireNonNull(
                expiresAt,
                "이메일 인증 만료 시각은(는) 필수입니다"
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

    public UUID getIdentityId() {
        return identityId;
    }

    public EmailChallengePurpose getPurpose() {
        return purpose;
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

}
