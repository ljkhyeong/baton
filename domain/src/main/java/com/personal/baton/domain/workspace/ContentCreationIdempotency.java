package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "content_creation_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_content_creation_idempotency_team_hash",
                columnNames = {"team_id", "idempotency_hash"}
        )
)
public class ContentCreationIdempotency {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ContentCreationOperation operation;

    @Column(name = "idempotency_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String idempotencyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    private String requestFingerprint;

    @Column(name = "resource_id", nullable = false, columnDefinition = "binary(16)")
    private UUID resourceId;

    protected ContentCreationIdempotency() {
    }

    private ContentCreationIdempotency(
            UUID id,
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyHash,
            String requestFingerprint,
            UUID resourceId
    ) {
        this.id = Objects.requireNonNull(id, "콘텐츠 생성 멱등 기록 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.operation = Objects.requireNonNull(operation, "콘텐츠 생성 작업 종류는 필수입니다");
        this.idempotencyHash = DomainAssertions.requiredSha256Hex(
                idempotencyHash,
                "멱등 키 해시"
        );
        this.requestFingerprint = DomainAssertions.requiredSha256Hex(
                requestFingerprint,
                "요청 지문"
        );
        this.resourceId = Objects.requireNonNull(resourceId, "생성 리소스 식별자는 필수입니다");
    }

    public static ContentCreationIdempotency create(
            UUID id,
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyHash,
            String requestFingerprint,
            UUID resourceId
    ) {
        return new ContentCreationIdempotency(
                id,
                teamId,
                seasonId,
                operation,
                idempotencyHash,
                requestFingerprint,
                resourceId
        );
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public ContentCreationOperation getOperation() {
        return operation;
    }

    public String getIdempotencyHash() {
        return idempotencyHash;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getResourceId() {
        return resourceId;
    }
}
