package com.personal.baton.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

public final class EffectiveClientAddress {

    private EffectiveClientAddress() {
    }

    public static String resolve(HttpServletRequest request) {
        return Objects.requireNonNullElse(
                Objects.requireNonNull(request).getRemoteAddr(),
                ""
        );
    }
}
