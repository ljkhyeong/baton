package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
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

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected RoleResource() {
    }

    private RoleResource(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "자료 식별자는 필수입니다");
        this.createdAt = Objects.requireNonNull(createdAt, "자료 생성 시각은 필수입니다");
        update(roleId, title, url, description);
    }

    public static RoleResource create(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt
    ) {
        return new RoleResource(id, roleId, title, url, description, createdAt);
    }

    public void update(UUID roleId, String title, String url, String description) {
        requireActive();
        UUID normalizedRoleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        String normalizedTitle = DomainAssertions.requiredText(title, "자료 제목", 200);
        String normalizedUrl = normalizeUrl(url);
        String normalizedDescription = DomainAssertions.optionalText(description, "자료 설명", 1000);

        this.roleId = normalizedRoleId;
        this.title = normalizedTitle;
        this.url = normalizedUrl;
        this.description = normalizedDescription;
    }

    public void updateArchive(boolean archived, Instant archivedAt) {
        if (archived) {
            if (this.archivedAt == null) {
                this.archivedAt = Objects.requireNonNull(archivedAt, "역할 자료 보관 시각은 필수입니다");
            }
            return;
        }
        this.archivedAt = null;
    }

    private void requireActive() {
        if (archivedAt != null) {
            throw new DomainValidationException("보관된 역할 자료는 수정할 수 없습니다");
        }
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
        boolean supportedScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!supportedScheme
                || !hasValidHost(uri)
                || hasUserInfo(uri)) {
            throw new DomainValidationException("자료 URL은 사용자 정보가 없는 http 또는 https 주소여야 합니다");
        }
        return normalized;
    }

    private static boolean hasValidHost(URI uri) {
        String parsedHost = uri.getHost();
        if (parsedHost != null && !parsedHost.isBlank()) {
            return hasValidPort(uri.getRawAuthority(), parsedHost, uri.getPort());
        }

        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null
                || rawAuthority.isBlank()
                || rawAuthority.indexOf('@') >= 0
                || rawAuthority.startsWith("[")
                || rawAuthority.endsWith(":")) {
            return false;
        }

        try {
            URL url = uri.toURL();
            String internationalizedHost = url.getHost();
            if (internationalizedHost == null || internationalizedHost.isBlank()) {
                return false;
            }
            return !IDN.toASCII(internationalizedHost, IDN.USE_STD3_ASCII_RULES).isBlank()
                    && hasValidPort(rawAuthority, internationalizedHost, url.getPort());
        } catch (IllegalArgumentException | MalformedURLException exception) {
            return false;
        }
    }

    private static boolean hasValidPort(String rawAuthority, String host, int parsedPort) {
        if (rawAuthority == null) {
            return false;
        }
        String rawHostAndPort = rawAuthority.substring(rawAuthority.lastIndexOf('@') + 1);
        boolean portOmitted = rawHostAndPort.equalsIgnoreCase(host)
                || rawHostAndPort.equalsIgnoreCase("[" + host + "]");
        if (portOmitted) {
            return true;
        }

        int portSeparator = rawHostAndPort.lastIndexOf(':');
        if (portSeparator < 0 || portSeparator == rawHostAndPort.length() - 1) {
            return false;
        }
        String rawPort = rawHostAndPort.substring(portSeparator + 1);
        boolean decimalPort = rawPort.chars()
                .allMatch(character -> character >= '0' && character <= '9');
        return decimalPort && parsedPort >= 0 && parsedPort <= 65_535;
    }

    private static boolean hasUserInfo(URI uri) {
        String rawAuthority = uri.getRawAuthority();
        return uri.getRawUserInfo() != null
                || (rawAuthority != null && rawAuthority.indexOf('@') >= 0);
    }

    public Long getVersion() {
        return version;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
