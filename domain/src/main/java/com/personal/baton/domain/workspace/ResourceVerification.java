package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "resource_verifications")
public class ResourceVerification {
    @Id
    @Column(columnDefinition = "binary(16)")
    private UUID id;
    @Column(name = "resource_id", nullable = false, columnDefinition = "binary(16)")
    private UUID resourceId;
    @Column(name = "resource_version", nullable = false)
    private long resourceVersion;
    @Column(name = "account_id", nullable = false, columnDefinition = "binary(16)")
    private UUID accountId;
    @Column(name = "member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID memberId;
    @Column(name = "member_name", nullable = false, length = 100)
    private String memberName;
    @Column(nullable = false, length = 2048)
    private String url;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceVerificationStatus status;
    @Column(length = 500)
    private String note;
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    protected ResourceVerification() {}

    public static ResourceVerification create(RoleResource resource, UUID accountId, Member member,
            ResourceVerificationStatus status, String note, Instant verifiedAt) {
        ResourceVerification result = new ResourceVerification();
        result.id = UUID.randomUUID();
        result.resourceId = resource.getId();
        result.resourceVersion = resource.getVersion();
        result.accountId = Objects.requireNonNull(accountId);
        result.memberId = member.getId();
        result.memberName = member.getName();
        result.url = resource.getUrl();
        result.status = Objects.requireNonNull(status, "확인 결과는 필수입니다");
        result.note = DomainAssertions.optionalText(note, "확인 메모", 500);
        result.verifiedAt = Objects.requireNonNull(verifiedAt);
        return result;
    }

    public UUID getId() { return id; }
    public UUID getResourceId() { return resourceId; }
    public long getResourceVersion() { return resourceVersion; }
    public UUID getAccountId() { return accountId; }
    public UUID getMemberId() { return memberId; }
    public String getMemberName() { return memberName; }
    public String getUrl() { return url; }
    public ResourceVerificationStatus getStatus() { return status; }
    public String getNote() { return note; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
