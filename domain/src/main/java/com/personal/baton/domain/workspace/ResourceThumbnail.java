package com.personal.baton.domain.workspace;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 자료에 표시할 외부 이미지의 허용 범위. */
public final class ResourceThumbnail {
    private static final Set<String> HOSTS = Set.of("i.ytimg.com", "i.vimeocdn.com", "secure-b.vimeocdn.com");

    private ResourceThumbnail() {
    }

    public static String normalize(String value) {
        String normalized = DomainAssertions.optionalText(value, "자료 썸네일 주소", 2048);
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) {
                return normalized;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 도메인 오류로 변환한다.
        }
        throw new DomainValidationException("자료 썸네일은 YouTube 또는 Vimeo의 HTTPS 이미지 주소여야 합니다");
    }
}
