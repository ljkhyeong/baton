package com.personal.baton.domain.workspace;

final class DomainAssertions {

    private DomainAssertions() {
    }

    static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + "은(는) 비어 있을 수 없습니다");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(field + "은(는) " + maxLength + "자를 초과할 수 없습니다");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(field + "은(는) " + maxLength + "자를 초과할 수 없습니다");
        }
        return normalized;
    }

    static String optionalTextOrEmpty(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        return normalized == null ? "" : normalized;
    }
}
