package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "role_resources")
public class RoleResource {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "role_id", nullable = false, columnDefinition = "binary(16)")
    private UUID roleId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 1000)
    private String description;

    @Version
    @Column(nullable = false)
    private Long version;

    protected RoleResource() {
    }

    private RoleResource(UUID id, UUID roleId, String title, String url, String description) {
        this.id = Objects.requireNonNull(id, "자료 식별자는 필수입니다");
        this.roleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        update(roleId, title, url, description);
    }

    public static RoleResource create(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description
    ) {
        return new RoleResource(id, roleId, title, url, description);
    }

    public void update(UUID roleId, String title, String url, String description) {
        UUID normalizedRoleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        String normalizedTitle = DomainAssertions.requiredText(title, "자료 제목", 200);
        String normalizedUrl = normalizeUrl(url);
        String normalizedDescription = DomainAssertions.optionalText(description, "자료 설명", 1000);

        this.roleId = normalizedRoleId;
        this.title = normalizedTitle;
        this.url = normalizedUrl;
        this.description = normalizedDescription;
    }

    private static String normalizeUrl(String value) {
        String normalized = DomainAssertions.requiredText(value, "자료 URL", 2048);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new DomainValidationException("자료 URL 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                || scheme.toLowerCase(Locale.ROOT).equals("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null) {
            throw new DomainValidationException("자료 URL은 사용자 정보가 없는 http 또는 https 주소여야 합니다");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }
}
