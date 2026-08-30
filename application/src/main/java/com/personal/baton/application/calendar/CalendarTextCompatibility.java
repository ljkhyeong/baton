package com.personal.baton.application.calendar;

import java.text.Normalizer;
import java.util.UUID;
import java.util.regex.Pattern;

final class CalendarTextCompatibility {

    private static final Pattern FORBIDDEN_TEXT = Pattern.compile(
            "[\\x{0}-\\x{8}\\x{B}-\\x{1F}\\x{7F}\\x{D800}-\\x{DFFF}]"
    );

    private CalendarTextCompatibility() {
    }

    static void require(UUID sourceId, String field, String value) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw incompatible(sourceId, field, "NFC 형식이 아닙니다");
        }
        if (FORBIDDEN_TEXT.matcher(value).find()) {
            throw incompatible(sourceId, field, "허용되지 않는 제어 문자 또는 서로게이트가 있습니다");
        }
    }

    private static IllegalStateException incompatible(UUID sourceId, String field, String reason) {
        return new IllegalStateException("CAL 보정 대상 " + sourceId + "의 " + field + "이(가) " + reason);
    }
}
