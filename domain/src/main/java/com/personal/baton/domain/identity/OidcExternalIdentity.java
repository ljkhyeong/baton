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
import java.util.regex.Pattern;

@Entity
@Table(
        name = "oidc_external_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oidc_external_identities_key_hash",
                columnNames = "identity_key_hash"
        )
)
public class OidcExternalIdentity {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "identity_key_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String identityKeyHash;

    @Column(nullable = false, length = 512)
    private String issuer;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "user_account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID userAccountId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected OidcExternalIdentity() {
    }

    private OidcExternalIdentity(
            UUID id,
            String identityKeyHash,
            String issuer,
            String subject,
            UUID userAccountId,
            Instant linkedAt
    ) {
        this.id = Objects.requireNonNull(id, "외부 신원 식별자는 필수입니다");
        this.identityKeyHash = requireHash(identityKeyHash);
        this.issuer = requireText(issuer, "OIDC issuer", 512);
        this.subject = requireText(subject, "OIDC subject", 255);
        this.userAccountId = Objects.requireNonNull(
                userAccountId,
                "외부 신원의 사용자 계정 식별자는 필수입니다"
        );
        this.linkedAt = Objects.requireNonNull(linkedAt, "외부 신원 연결 시각은 필수입니다");
    }

    public static OidcExternalIdentity link(
            UUID id,
            String identityKeyHash,
            String issuer,
            String subject,
            UUID userAccountId,
            Instant linkedAt
    ) {
        return new OidcExternalIdentity(
                id,
                identityKeyHash,
                issuer,
                subject,
                userAccountId,
                linkedAt
        );
    }

    public boolean represents(String issuer, String subject) {
        return this.issuer.equals(issuer) && this.subject.equals(subject);
    }

    public UUID getId() {
        return id;
    }

    public String getIdentityKeyHash() {
        return identityKeyHash;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getUserAccountId() {
        return userAccountId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    private static String requireHash(String value) {
        String validated = requireText(value, "외부 신원 키 해시", 64);
        if (!SHA256_HEX.matcher(validated).matches()) {
            throw new IllegalArgumentException("외부 신원 키 해시는 SHA-256 hex 형식이어야 합니다");
        }
        return validated;
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field + "은(는) 필수입니다");
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + "은(는) 비어 있지 않고 " + maximumLength + "자 이하여야 합니다"
            );
        }
        return value;
    }
}
