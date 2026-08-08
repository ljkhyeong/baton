package com.personal.baton.domain.roundauth;

import java.util.Objects;
import java.util.regex.Pattern;

public record RoundRoomId(String value) {

    private static final Pattern PATTERN = Pattern.compile(
            "[abcdefghjkmnpqrstuvwxyz23456789]{4}-"
                    + "[abcdefghjkmnpqrstuvwxyz23456789]{4}-"
                    + "[abcdefghjkmnpqrstuvwxyz23456789]{4}"
    );

    public RoundRoomId {
        Objects.requireNonNull(value, "ROUND 방 식별자는 필수입니다");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("ROUND 방 식별자 형식이 올바르지 않습니다");
        }
    }

    public static boolean isCanonical(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
