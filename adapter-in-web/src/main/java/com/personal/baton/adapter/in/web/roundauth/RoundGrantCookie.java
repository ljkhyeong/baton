package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.domain.roundauth.RoundRoomId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

final class RoundGrantCookie {

    static final String NAME = "__Secure-round_access";

    private RoundGrantCookie() {
    }

    static ResponseCookie issue(
            String roomId,
            String token,
            long expiresAtEpochSecond,
            Clock clock
    ) {
        Instant expiresAt = Instant.ofEpochSecond(expiresAtEpochSecond);
        long remainingSeconds = Math.max(
                0,
                Duration.between(clock.instant(), expiresAt).getSeconds()
        );
        return base(roomId, token)
                .maxAge(Duration.ofSeconds(remainingSeconds))
                .build();
    }

    static ResponseCookie expire(String roomId) {
        return base(roomId, "")
                .maxAge(Duration.ZERO)
                .build();
    }

    static String path(String roomId) {
        return "/round/rooms/" + new RoundRoomId(roomId).value();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String roomId, String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path(roomId));
    }
}
