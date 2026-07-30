package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    JsonAccessDeniedHandler(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException, ServletException {
        if (exception instanceof CsrfException) {
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "CSRF_TOKEN_INVALID",
                    "CSRF 토큰이 없거나 올바르지 않습니다"
            );
            return;
        }
        responseWriter.write(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                "요청한 작업을 수행할 권한이 없습니다"
        );
    }
}
