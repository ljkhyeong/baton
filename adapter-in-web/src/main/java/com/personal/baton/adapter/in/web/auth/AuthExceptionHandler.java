package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.HttpObservationErrors;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.PasswordResetException;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.error.CurrentPasswordMismatchException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.error.LocalPasswordUnavailableException;
import com.personal.baton.domain.identity.IdentityValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        AuthController.class,
        AccountSecurityController.class,
        AccountDeactivationController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(CurrentPasswordMismatchException.class)
    public ResponseEntity<ErrorResponse> currentPasswordMismatch(
            CurrentPasswordMismatchException exception
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "CURRENT_PASSWORD_INVALID",
                exception.getMessage()
        );
    }

    @ExceptionHandler(LocalPasswordUnavailableException.class)
    public ResponseEntity<ErrorResponse> localPasswordUnavailable(
            LocalPasswordUnavailableException exception
    ) {
        return error(
                HttpStatus.CONFLICT,
                "LOCAL_PASSWORD_UNAVAILABLE",
                exception.getMessage()
        );
    }

    @ExceptionHandler(PasswordResetException.class)
    public ResponseEntity<ErrorResponse> passwordReset(PasswordResetException exception) {
        return error(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_INVALID", exception.getMessage());
    }

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

    @ExceptionHandler({
            EmailVerificationDeliveryUnavailableException.class,
            EmailVerificationPayloadProtectionException.class
    })
    public ResponseEntity<ErrorResponse> handleEmailVerificationUnavailable(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        HttpObservationErrors.mark(request, exception);
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

    @ExceptionHandler({
            IdentityOperationUnavailableException.class,
            CannotCreateTransactionException.class,
            TransactionTimedOutException.class,
            TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleIdentityInfrastructureUnavailable(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        HttpObservationErrors.mark(
                request,
                IdentityInfrastructureFailures.find(exception).orElse(exception)
        );
        return identityUnavailable();
    }

    private ResponseEntity<ErrorResponse> identityUnavailable() {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "IDENTITY_TEMPORARILY_UNAVAILABLE",
                "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
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
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
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
