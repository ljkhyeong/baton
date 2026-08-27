package com.personal.baton.adapter.in.web.security;

import com.personal.baton.adapter.in.web.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

public final class SecurityErrorResponseWriter {

    private final ObjectWriter errorWriter;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.errorWriter = Objects.requireNonNull(objectMapper).writerFor(ErrorResponse.class);
    }

    public void write(
            HttpServletResponse response,
            int status,
            ErrorResponse errorResponse
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(errorWriter.writeValueAsString(
                Objects.requireNonNull(errorResponse)
        ));
    }
}
