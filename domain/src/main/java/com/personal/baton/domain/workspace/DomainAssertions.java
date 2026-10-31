package com.personal.baton.domain.workspace;

import java.time.LocalDate;
import java.util.regex.Pattern;

final class DomainAssertions {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    // MySQL DATE·DATETIME 범위(1000~9999년)에서 마감 오프셋, 종일 일정 종료일, 회차 커서 전진이 넘치지 않도록 앞뒤 1년을 남긴다.
    private static final int MIN_SUPPORTED_YEAR = 1001;
    private static final int MAX_SUPPORTED_YEAR = 9998;

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
        return value == null || value.isBlank() ? null : requiredText(value, field, maxLength);
    }

    static LocalDate supportedDate(LocalDate value, String field) {
        if (value.getYear() < MIN_SUPPORTED_YEAR || value.getYear() > MAX_SUPPORTED_YEAR) {
            throw new DomainValidationException(
                    field + "은(는) " + MIN_SUPPORTED_YEAR + "년부터 " + MAX_SUPPORTED_YEAR + "년 사이여야 합니다"
            );
        }
        return value;
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
