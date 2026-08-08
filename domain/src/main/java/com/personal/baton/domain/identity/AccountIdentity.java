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
        name = "account_identities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_account_identities_provider_subject",
                        columnNames = {"provider", "provider_subject"}
                ),
                @UniqueConstraint(
                        name = "uk_account_identities_account_provider",
                        columnNames = {"account_id", "provider"}
                )
        }
)
public class AccountIdentity {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 320)
    private String providerSubject;

    @Column(name = "email_snapshot", length = 320)
    private String emailSnapshot;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected AccountIdentity() {
    }

    private AccountIdentity(
            UUID id,
            UUID accountId,
            IdentityProvider provider,
            String providerSubject,
            String emailSnapshot,
            boolean emailVerified,
            Instant createdAt,
            Instant lastAuthenticatedAt
    ) {
        this.id = Objects.requireNonNull(id, "계정 신원 식별자는 필수입니다");
        this.accountId = Objects.requireNonNull(accountId, "계정 식별자는 필수입니다");
        this.provider = Objects.requireNonNull(provider, "신원 제공자는 필수입니다");
        this.providerSubject = IdentityAssertions.requiredProviderSubject(providerSubject);
        this.emailSnapshot = IdentityAssertions.optionalEmail(emailSnapshot);
        this.emailVerified = this.emailSnapshot != null && emailVerified;
        this.createdAt = IdentityAssertions.requiredInstant(createdAt, "신원 생성 시각");
        this.lastAuthenticatedAt = lastAuthenticatedAt;
        if (lastAuthenticatedAt != null && lastAuthenticatedAt.isBefore(this.createdAt)) {
            throw new IdentityValidationException(
                    "마지막 인증 시각은 신원 생성 시각보다 빠를 수 없습니다"
            );
        }
    }

    public static AccountIdentity createLocal(
            UUID id,
            UUID accountId,
            String email,
            Instant createdAt
    ) {
        String normalizedEmail = normalizeLocalEmail(email);
        return new AccountIdentity(
                id,
                accountId,
                IdentityProvider.LOCAL_EMAIL,
                normalizedEmail,
                normalizedEmail,
                false,
                createdAt,
                null
        );
    }

    public static AccountIdentity createExternal(
            UUID id,
            UUID accountId,
            IdentityProvider provider,
            String providerSubject,
            String email,
            boolean emailVerified,
            Instant authenticatedAt
    ) {
        requireExternal(provider);
        Instant now = IdentityAssertions.requiredInstant(authenticatedAt, "외부 인증 시각");
        return new AccountIdentity(
                id,
                accountId,
                provider,
                providerSubject,
                email,
                emailVerified,
                now,
                now
        );
    }

    public static String normalizeLocalEmail(String email) {
        return IdentityAssertions.normalizeEmail(email, "로컬 계정 이메일");
    }

    public static String normalizeExternalSubject(String providerSubject) {
        return IdentityAssertions.requiredProviderSubject(providerSubject);
    }

    public void recordExternalAuthentication(
            String email,
            boolean emailVerified,
            Instant authenticatedAt
    ) {
        requireExternal(provider);
        Instant now = IdentityAssertions.requiredInstant(authenticatedAt, "외부 인증 시각");
        if (email != null && !email.isBlank()) {
            this.emailSnapshot = IdentityAssertions.optionalEmail(email);
            this.emailVerified = emailVerified;
        }
        if (lastAuthenticatedAt == null || now.isAfter(lastAuthenticatedAt)) {
            this.lastAuthenticatedAt = now;
        }
    }

    public void verifyLocalEmail() {
        if (provider != IdentityProvider.LOCAL_EMAIL) {
            throw new IdentityValidationException("로컬 이메일 신원만 이메일 인증할 수 있습니다");
        }
        this.emailVerified = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getEmailSnapshot() {
        return emailSnapshot;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public Long getVersion() {
        return version;
    }

    private static void requireExternal(IdentityProvider provider) {
        if (provider == null || !provider.isExternal()) {
            throw new IdentityValidationException(
                    "Google 또는 Naver 신원만 외부 신원으로 사용할 수 있습니다"
            );
        }
    }
}
