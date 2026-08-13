package com.personal.baton.adapter.out.external.identity;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.identity.email-verification")
public record IdentityEmailVerificationProperties(
        @DefaultValue("disabled") Delivery delivery,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue("") String fromAddress,
        @DefaultValue("") String outboxEncryptionKey
) {

    public URI requiredPublicOrigin() {
        URI uri;
        try {
            uri = URI.create(publicBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("이메일 인증 공개 주소는 유효한 URI여야 합니다", exception);
        }
        boolean rootPath = uri.getPath() == null
                || uri.getPath().isEmpty()
                || "/".equals(uri.getPath());
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !rootPath
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "이메일 인증 공개 주소는 path, user info, query, fragment가 없는 HTTPS origin이어야 합니다"
            );
        }
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            throw new IllegalStateException("이메일 인증 공개 주소의 포트가 올바르지 않습니다");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    @Override
    public String toString() {
        return "IdentityEmailVerificationProperties[delivery=" + delivery
                + ", publicBaseUrl=" + publicBaseUrl
                + ", fromAddress=" + fromAddress
                + ", outboxEncryptionKey=<redacted>]";
    }

    public enum Delivery {
        DISABLED,
        SMTP
    }
}
