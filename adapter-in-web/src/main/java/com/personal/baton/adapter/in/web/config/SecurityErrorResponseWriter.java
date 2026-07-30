package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

final class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
    }
}
