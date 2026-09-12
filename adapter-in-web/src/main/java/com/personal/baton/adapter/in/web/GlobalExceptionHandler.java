package com.personal.baton.adapter.in.web;

import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.identity.error.AccountDeactivationBlockedException;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.application.watch.error.WatchHealthEventConflictException;
import com.personal.baton.application.watch.error.WatchHealthEventChangedAtOutOfRangeException;
import com.personal.baton.application.watch.error.WatchHealthEventIdMismatchException;
import com.personal.baton.application.watch.error.WatchHealthEventResourceReferenceException;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.ResourceCheckRequestException;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoleHandoffTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountDeactivationBlockedException.class)
    public ResponseEntity<ErrorResponse> accountDeactivationBlocked(AccountDeactivationBlockedException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_DEACTIVATION_BLOCKED", exception, request);
    }

    @ExceptionHandler(AccountDeactivatedException.class)
    public ResponseEntity<ErrorResponse> accountDeactivated(AccountDeactivatedException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED", exception, request);
    }

    @ExceptionHandler(AccountMembershipConflictException.class)
    public ResponseEntity<ErrorResponse> handleAccountMembershipConflict(
            AccountMembershipConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_MEMBERSHIP_CONFLICT", exception, request);
    }

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ErrorResponse INTERNAL_ERROR = new ErrorResponse(
            "INTERNAL_ERROR",
            "서버에서 요청을 처리하지 못했습니다"
    );

    @ExceptionHandler(ResourceCheckRequestException.class)
    public ResponseEntity<ErrorResponse> handleResourceCheckRequest(ResourceCheckRequestException exception, HttpServletRequest request) {
        HttpObservationErrors.mark(request, exception);
        HttpStatus status = switch (exception.reason()) {
            case INACTIVE -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
        }
        return response.body(new ErrorResponse("WATCH_CHECK_" + exception.reason().name(), exception.getMessage()));
    }

    @ExceptionHandler(WorkspaceCreationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceCreationDenied(
            WorkspaceCreationDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "WORKSPACE_CREATION_DENIED",
                exception,
                request
        );
    }

    @ExceptionHandler(WorkspaceRecoveryDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceRecoveryDenied(
            WorkspaceRecoveryDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "WORKSPACE_RECOVERY_DENIED",
                exception,
                request
        );
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceAccessDenied(
            WorkspaceAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "WORKSPACE_ACCESS_DENIED",
                exception,
                request
        );
    }

    @ExceptionHandler(WorkspaceAccessKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceAccessKeyConflict(
            WorkspaceAccessKeyConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "WORKSPACE_ACCESS_KEY_CONFLICT",
                exception,
                request
        );
    }

    @ExceptionHandler(WorkspaceContentConflictException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceContentConflict(
            WorkspaceContentConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "WORKSPACE_CONTENT_CONFLICT",
                exception,
                request
        );
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(
            WorkspaceNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                exception.getCode(),
                exception,
                request
        );
    }

    @ExceptionHandler(MemberNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleMemberNameConflict(
            MemberNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "MEMBER_NAME_CONFLICT",
                exception,
                request
        );
    }

    @ExceptionHandler(RoleNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleRoleNameConflict(
            RoleNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "ROLE_NAME_CONFLICT", exception, request);
    }

    @ExceptionHandler({
            RoleHandoffStateConflictException.class,
            RoleHandoffTransitionException.class
    })
    public ResponseEntity<ErrorResponse> handleRoleHandoffStateConflict(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ROLE_HANDOFF_STATE_CONFLICT",
                exception,
                request
        );
    }

    @ExceptionHandler(RoleHandoffWarningConfirmationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleRoleHandoffWarningConfirmationRequired(
            RoleHandoffWarningConfirmationRequiredException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED",
                exception,
                request
        );
    }

    @ExceptionHandler(SeasonNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleSeasonNameConflict(
            SeasonNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "SEASON_NAME_CONFLICT", exception, request);
    }

    @ExceptionHandler(SeasonEndedException.class)
    public ResponseEntity<ErrorResponse> handleSeasonEnded(
            SeasonEndedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "SEASON_ENDED", exception, request);
    }

    @ExceptionHandler(SeasonSuccessorExistsException.class)
    public ResponseEntity<ErrorResponse> handleSeasonSuccessorExists(
            SeasonSuccessorExistsException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "SEASON_SUCCESSOR_EXISTS",
                exception,
                request
        );
    }

    @ExceptionHandler(SeasonRoundNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleSeasonRoundNameConflict(
            SeasonRoundNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "ROUND_NAME_CONFLICT", exception, request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception, request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception, request);
    }

    @ExceptionHandler(IdempotencyReplayExpiredException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyReplayExpired(
            IdempotencyReplayExpiredException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY_EXPIRED", exception, request);
    }

    @ExceptionHandler(WatchHealthEventIdMismatchException.class)
    public ResponseEntity<ErrorResponse> handleWatchHealthEventIdMismatch(
            WatchHealthEventIdMismatchException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_MISMATCH",
                exception,
                request
        );
    }

    @ExceptionHandler(WatchHealthEventResourceReferenceException.class)
    public ResponseEntity<ErrorResponse> handleWatchHealthEventResourceReference(
            WatchHealthEventResourceReferenceException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "WATCH_RESOURCE_REFERENCE_INVALID",
                exception,
                request
        );
    }

    @ExceptionHandler(WatchHealthEventChangedAtOutOfRangeException.class)
    public ResponseEntity<ErrorResponse> handleWatchHealthEventChangedAtOutOfRange(
            WatchHealthEventChangedAtOutOfRangeException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception, request);
    }

    @ExceptionHandler(WatchHealthEventConflictException.class)
    public ResponseEntity<ErrorResponse> handleWatchHealthEventConflict(
            WatchHealthEventConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "WATCH_EVENT_ID_CONFLICT",
                exception,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                new ErrorResponse("INVALID_INPUT", "요청 본문 형식이 올바르지 않습니다"),
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(error -> error.getField()))
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return handleExceptionInternal(exception, new ErrorResponse("INVALID_INPUT", message), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                new ErrorResponse("INVALID_INPUT", "요청 값 형식이 올바르지 않습니다"),
                headers,
                status,
                request
        );
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ErrorResponse> handleDomainValidation(
            DomainValidationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        HttpServletRequest servletRequest = servletRequest(request);
        HttpObservationErrors.mark(servletRequest, exception);
        if (status.is5xxServerError()) {
            logUnexpected(exception, servletRequest);
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        if (status.isSameCodeAs(HttpStatus.NOT_ACCEPTABLE)) {
            return super.createResponseEntity(null, headers, status, request);
        }
        Object normalizedBody = body instanceof ErrorResponse ? body : frameworkError(status);
        return super.createResponseEntity(normalizedBody, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleAsyncRequestNotUsableException(
            AsyncRequestNotUsableException exception,
            WebRequest request
    ) {
        HttpObservationErrors.mark(servletRequest(request), exception);
        return super.handleAsyncRequestNotUsableException(exception, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(
            Exception exception,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                INTERNAL_ERROR,
                HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR,
                request
        );
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status,
            String code,
            Exception exception,
            HttpServletRequest request
    ) {
        HttpObservationErrors.mark(request, exception);
        return ResponseEntity.status(status).body(new ErrorResponse(code, exception.getMessage()));
    }

    private ErrorResponse frameworkError(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> new ErrorResponse("INVALID_INPUT", "요청 값이 올바르지 않습니다");
            case 404 -> new ErrorResponse("RESOURCE_NOT_FOUND", "요청한 경로를 찾을 수 없습니다");
            case 405 -> new ErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다");
            case 415 -> new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 본문 형식입니다");
            default -> status.is4xxClientError()
                    ? new ErrorResponse("INVALID_INPUT", "요청을 처리할 수 없습니다")
                    : INTERNAL_ERROR;
        };
    }

    private HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest()
                : null;
    }

    private void logUnexpected(Exception exception, HttpServletRequest request) {
        if (request == null) {
            LOG.error("Unexpected exception while handling an HTTP request", exception);
            return;
        }
        RequestIdFilter.markServerErrorLogged(request);
        LOG.error(
                "Unexpected exception while handling HTTP request: method={}, path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
    }
}
