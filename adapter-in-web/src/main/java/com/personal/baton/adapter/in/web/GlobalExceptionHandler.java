package com.personal.baton.adapter.in.web;

import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.domain.workspace.DomainValidationException;
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
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ErrorResponse INTERNAL_ERROR = new ErrorResponse(
            "INTERNAL_ERROR",
            "서버에서 요청을 처리하지 못했습니다"
    );

    @ExceptionHandler(WorkspaceCreationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceCreationDenied(
            WorkspaceCreationDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "WORKSPACE_CREATION_DENIED",
                exception.getMessage(),
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
                exception.getMessage(),
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
                exception.getMessage(),
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
                exception.getMessage(),
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
                exception.getMessage(),
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
                exception.getMessage(),
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
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(RoleNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleRoleNameConflict(
            RoleNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "ROLE_NAME_CONFLICT", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(SeasonRoundNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleSeasonRoundNameConflict(
            SeasonRoundNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "ROUND_NAME_CONFLICT", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(IdempotencyReplayExpiredException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyReplayExpired(
            IdempotencyReplayExpiredException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY_EXPIRED", exception.getMessage(), exception, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return mvcError(
                status,
                headers,
                new ErrorResponse("INVALID_INPUT", "요청 본문 형식이 올바르지 않습니다"),
                exception,
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
        return mvcError(status, headers, new ErrorResponse("INVALID_INPUT", message), exception, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return mvcError(
                status,
                headers,
                new ErrorResponse("INVALID_INPUT", "요청 값 형식이 올바르지 않습니다"),
                exception,
                request
        );
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ErrorResponse> handleDomainValidation(
            DomainValidationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception.getMessage(), exception, request);
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
        markObservationError(servletRequest, exception);
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
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) {
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
        markObservationError(servletRequest(request), exception);
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
            String message,
            Exception exception,
            HttpServletRequest request
    ) {
        markObservationError(request, exception);
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

    private ResponseEntity<Object> mvcError(
            HttpStatusCode status,
            HttpHeaders headers,
            ErrorResponse response,
            Exception exception,
            WebRequest request
    ) {
        return handleExceptionInternal(exception, response, headers, status, request);
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

    private void markObservationError(HttpServletRequest request, Exception exception) {
        if (request == null) {
            return;
        }
        ServerHttpObservationFilter.findObservationContext(request)
                .ifPresent(context -> context.setError(exception));
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
