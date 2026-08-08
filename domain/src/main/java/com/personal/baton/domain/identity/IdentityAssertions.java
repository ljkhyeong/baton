package com.personal.baton.domain.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class IdentityAssertions {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private IdentityAssertions() {
    }

    static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IdentityValidationException(field + "은(는) 비어 있을 수 없습니다");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IdentityValidationException(
                    field + "은(는) " + maxLength + "자를 초과할 수 없습니다"
            );
        }
        return normalized;
    }

    static String requiredProviderSubject(String value) {
        String subject = requiredText(value, "제공자 사용자 식별자", 320);
        if (!subject.equals(value) || containsControlCharacter(subject)) {
            throw new IdentityValidationException(
                    "제공자 사용자 식별자의 앞뒤 공백과 제어 문자는 허용하지 않습니다"
            );
        }
        return subject;
    }

    static String normalizeEmail(String value, String field) {
        String email = requiredText(value, field, 320).toLowerCase(Locale.ROOT);
        if (containsControlCharacter(email) || email.chars().anyMatch(Character::isWhitespace)) {
            throw new IdentityValidationException(field + " 형식이 올바르지 않습니다");
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            throw new IdentityValidationException(field + " 형식이 올바르지 않습니다");
        }
        String domain = email.substring(at + 1);
        if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".")) {
            throw new IdentityValidationException(field + " 형식이 올바르지 않습니다");
        }
        return email;
    }

    static String optionalEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeEmail(value, "이메일");
    }

    static String requiredSha256Hex(String value, String field) {
        if (value == null || !SHA_256_HEX.matcher(value).matches()) {
            throw new IdentityValidationException(field + "은(는) SHA-256 16진수여야 합니다");
        }
        return value;
    }

    static String requiredOpaqueHash(String value) {
        String hash = requiredText(value, "비밀번호 해시", 255);
        if (containsControlCharacter(hash)) {
            throw new IdentityValidationException("비밀번호 해시에 제어 문자를 사용할 수 없습니다");
        }
        return hash;
    }

    static Instant requiredInstant(Instant value, String field) {
        return Objects.requireNonNull(value, field + "은(는) 필수입니다");
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
