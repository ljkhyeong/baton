package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "access_key_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String accessKeyHash;

    @Column(name = "idempotency_key_hash", length = 64, columnDefinition = "char(64)")
    private String idempotencyKeyHash;

    @Column(name = "creation_request_fingerprint", length = 64, columnDefinition = "char(64)")
    private String creationRequestFingerprint;

    @Column(name = "creation_season_id", columnDefinition = "binary(16)")
    private UUID creationSeasonId;

    @Column(name = "last_access_key_change_idempotency_hash", length = 64, columnDefinition = "char(64)")
    private String lastAccessKeyChangeIdempotencyHash;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Team() {
    }

    private Team(UUID id, String name, String accessKeyHash) {
        this.id = Objects.requireNonNull(id, "팀 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "팀 이름", 100);
        this.accessKeyHash = DomainAssertions.requiredSha256Hex(
                accessKeyHash,
                "접근 키 해시"
        );
    }

    public static Team create(UUID id, String name, String accessKeyHash) {
        return new Team(id, name, accessKeyHash);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAccessKeyHash() {
        return accessKeyHash;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getCreationRequestFingerprint() {
        return creationRequestFingerprint;
    }

    public UUID getCreationSeasonId() {
        return creationSeasonId;
    }

    public String getLastAccessKeyChangeIdempotencyHash() {
        return lastAccessKeyChangeIdempotencyHash;
    }

    public Long getVersion() {
        return version;
    }

    public void changeAccessKey(String newAccessKeyHash, String idempotencyHash) {
        String validatedAccessKeyHash = DomainAssertions.requiredSha256Hex(
                newAccessKeyHash,
                "접근 키 해시"
        );
        String validatedIdempotencyHash = DomainAssertions.requiredSha256Hex(
                idempotencyHash,
                "접근 키 변경 멱등 키 해시"
        );
        this.accessKeyHash = validatedAccessKeyHash;
        this.lastAccessKeyChangeIdempotencyHash = validatedIdempotencyHash;
    }

    public void recordCreationRequest(
            String newIdempotencyKeyHash,
            String newCreationRequestFingerprint,
            UUID newCreationSeasonId
    ) {
        if (idempotencyKeyHash != null || creationRequestFingerprint != null || creationSeasonId != null) {
            throw new DomainValidationException("워크스페이스 생성 요청 정보는 한 번만 기록할 수 있습니다");
        }
        String validatedIdempotencyKeyHash = DomainAssertions.requiredSha256Hex(
                newIdempotencyKeyHash,
                "멱등 키 해시"
        );
        String validatedRequestFingerprint = DomainAssertions.requiredSha256Hex(
                newCreationRequestFingerprint,
                "워크스페이스 생성 요청 지문"
        );
        UUID validatedCreationSeasonId = Objects.requireNonNull(
                newCreationSeasonId,
                "생성 시즌 식별자는 필수입니다"
        );
        this.idempotencyKeyHash = validatedIdempotencyKeyHash;
        this.creationRequestFingerprint = validatedRequestFingerprint;
        this.creationSeasonId = validatedCreationSeasonId;
    }

}
