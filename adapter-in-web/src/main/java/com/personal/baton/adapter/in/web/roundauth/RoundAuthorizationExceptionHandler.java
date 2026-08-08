package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.error.ParticipationGrantUnavailableException;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.error.RoundRoomConflictException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ParticipationGrantController.class,
        RoundAdministrationController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RoundAuthorizationExceptionHandler {

    @ExceptionHandler(RoundAuthenticationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationRequired(
            RoundAuthenticationRequiredException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                exception.getMessage(),
                request,
                true
        );
    }

    @ExceptionHandler(RoundParticipationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleParticipationDenied(
            RoundParticipationDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "ROUND_PARTICIPATION_DENIED",
                exception.getMessage(),
                request,
                true
        );
    }

    @ExceptionHandler(RoundRoomNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoomNotFound(
            RoundRoomNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "ROUND_ROOM_NOT_FOUND",
                exception.getMessage(),
                request,
                true
        );
    }

    @ExceptionHandler(AccountMembershipConflictException.class)
    public ResponseEntity<ErrorResponse> handleMembershipConflict(
            AccountMembershipConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ACCOUNT_MEMBERSHIP_CONFLICT",
                exception.getMessage(),
                request,
                false
        );
    }

    @ExceptionHandler(RoundRoomConflictException.class)
    public ResponseEntity<ErrorResponse> handleRoomConflict(
            RoundRoomConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ROUND_ROOM_CONFLICT",
                exception.getMessage(),
                request,
                false
        );
    }

    @ExceptionHandler(ParticipationGrantUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleGrantUnavailable(
            ParticipationGrantUnavailableException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PARTICIPATION_GRANT_UNAVAILABLE",
                "ROUND 참여권을 현재 발급할 수 없습니다",
                request,
                false
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_INPUT",
                exception.getMessage(),
                request,
                false
        );
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            boolean expireGrant
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore());
        String roomId = refreshRoomId(request);
        if (expireGrant && roomId != null) {
            builder.header(HttpHeaders.SET_COOKIE, RoundGrantCookie.expire(roomId).toString());
        }
        return builder.body(new ErrorResponse(code, message));
    }

    private String refreshRoomId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = "/round/rooms/";
        String suffix = "/participation-grant/refresh";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String roomId = path.substring(prefix.length(), path.length() - suffix.length());
        try {
            RoundGrantCookie.path(roomId);
            return roomId;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
