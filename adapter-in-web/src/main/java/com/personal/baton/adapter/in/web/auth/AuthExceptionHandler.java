package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.domain.identity.IdentityValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(EmailVerificationException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerification(
            EmailVerificationException exception
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "EMAIL_VERIFICATION_INVALID",
                "이메일 인증 요청이 올바르지 않거나 만료되었습니다"
        );
    }

    @ExceptionHandler(EmailVerificationDeliveryUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleEmailDeliveryUnavailable(
            EmailVerificationDeliveryUnavailableException exception
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_VERIFICATION_UNAVAILABLE",
                "현재 이메일 인증을 시작할 수 없습니다"
        );
    }

    @ExceptionHandler(EmailVerificationPayloadProtectionException.class)
    public ResponseEntity<ErrorResponse> handleEmailPayloadProtectionUnavailable(
            EmailVerificationPayloadProtectionException exception
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_VERIFICATION_UNAVAILABLE",
                "현재 이메일 인증을 시작할 수 없습니다"
        );
    }

    @ExceptionHandler(IdentityConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdentityConflict(
            IdentityConflictException exception
    ) {
        return error(
                HttpStatus.CONFLICT,
                "IDENTITY_CONFLICT",
                "요청한 신원을 사용할 수 없습니다"
        );
    }

    @ExceptionHandler({IdentityValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception.getMessage());
    }

    @ExceptionHandler(AuthRateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            AuthRateLimitExceededException exception
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(
                        "AUTH_RATE_LIMITED",
                        "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "인증된 계정이 필요합니다");
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(code, message));
    }
}
