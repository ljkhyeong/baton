package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.HttpObservationErrors;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class AccountAuthenticationFailureHandler
        implements AuthenticationFailureHandler {

    private static final ErrorResponse IDENTITY_TEMPORARILY_UNAVAILABLE = new ErrorResponse(
            "IDENTITY_TEMPORARILY_UNAVAILABLE",
            "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
    );
    private static final ErrorResponse INVALID_CREDENTIALS = new ErrorResponse(
            "INVALID_CREDENTIALS",
            "이메일 또는 비밀번호가 올바르지 않습니다"
    );

    private final SecurityErrorResponseWriter errorResponseWriter;
    private final AuthRateLimiter rateLimiter;

    public AccountAuthenticationFailureHandler(
            SecurityErrorResponseWriter errorResponseWriter,
            AuthRateLimiter rateLimiter
    ) {
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        Optional<RuntimeException> infrastructureFailure =
                IdentityInfrastructureFailures.find(exception);
        if (infrastructureFailure.isPresent()) {
            rateLimiter.recordLoginInfrastructureFailure(request.getParameter("email"));
            HttpObservationErrors.mark(request, infrastructureFailure.get());
            errorResponseWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    IDENTITY_TEMPORARILY_UNAVAILABLE
            );
            return;
        }
        errorResponseWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                INVALID_CREDENTIALS
        );
    }

}
