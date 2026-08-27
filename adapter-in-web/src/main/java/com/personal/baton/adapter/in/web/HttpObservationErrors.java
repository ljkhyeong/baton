package com.personal.baton.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;

public final class HttpObservationErrors {

    private HttpObservationErrors() {
    }

    public static void mark(HttpServletRequest request, Exception exception) {
        if (request == null) {
            return;
        }
        ServerHttpObservationFilter.findObservationContext(request)
                .ifPresent(context -> context.setError(exception));
    }
}
