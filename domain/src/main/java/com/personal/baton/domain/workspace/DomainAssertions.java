package com.personal.baton.domain.workspace;

import java.util.regex.Pattern;

final class DomainAssertions {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private DomainAssertions() {
    }

    static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + "은(는) 비어 있을 수 없습니다");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(field + "은(는) " + maxLength + "자를 초과할 수 없습니다");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(field + "은(는) " + maxLength + "자를 초과할 수 없습니다");
        }
        return normalized;
    }

    static String optionalTextOrEmpty(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        return normalized == null ? "" : normalized;
    }

    static String requiredSha256Hex(String value, String field) {
        if (value == null || !SHA_256_HEX.matcher(value).matches()) {
            throw new DomainValidationException(
                    field + "은(는) SHA-256 16진수여야 합니다"
            );
        }
        return value;
    }
}
