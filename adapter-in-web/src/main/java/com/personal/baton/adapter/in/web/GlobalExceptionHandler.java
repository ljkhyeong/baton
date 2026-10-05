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
import java.util.Map;
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

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ErrorResponse INTERNAL_ERROR = new ErrorResponse(
            "INTERNAL_ERROR",
            "서버에서 요청을 처리하지 못했습니다"
    );

    private record ErrorMapping(HttpStatus status, String code) {
    }

    // 상태와 오류 코드만 다른 애플리케이션 예외를 한곳에서 응답으로 바꾼다.
    private static final Map<Class<? extends RuntimeException>, ErrorMapping> ERROR_MAPPINGS = Map.ofEntries(
            mapping(AccountDeactivationBlockedException.class, HttpStatus.CONFLICT, "ACCOUNT_DEACTIVATION_BLOCKED"),
            mapping(AccountDeactivatedException.class, HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED"),
            mapping(AccountMembershipConflictException.class, HttpStatus.CONFLICT, "ACCOUNT_MEMBERSHIP_CONFLICT"),
            mapping(WorkspaceCreationDeniedException.class, HttpStatus.FORBIDDEN, "WORKSPACE_CREATION_DENIED"),
            mapping(WorkspaceRecoveryDeniedException.class, HttpStatus.FORBIDDEN, "WORKSPACE_RECOVERY_DENIED"),
            mapping(WorkspaceAccessDeniedException.class, HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED"),
            mapping(WorkspaceAccessKeyConflictException.class, HttpStatus.CONFLICT, "WORKSPACE_ACCESS_KEY_CONFLICT"),
            mapping(WorkspaceContentConflictException.class, HttpStatus.CONFLICT, "WORKSPACE_CONTENT_CONFLICT"),
            mapping(MemberNameConflictException.class, HttpStatus.CONFLICT, "MEMBER_NAME_CONFLICT"),
            mapping(RoleNameConflictException.class, HttpStatus.CONFLICT, "ROLE_NAME_CONFLICT"),
            mapping(RoleHandoffStateConflictException.class, HttpStatus.CONFLICT, "ROLE_HANDOFF_STATE_CONFLICT"),
            mapping(RoleHandoffTransitionException.class, HttpStatus.CONFLICT, "ROLE_HANDOFF_STATE_CONFLICT"),
            mapping(RoleHandoffWarningConfirmationRequiredException.class, HttpStatus.CONFLICT,
                    "ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED"),
            mapping(SeasonNameConflictException.class, HttpStatus.CONFLICT, "SEASON_NAME_CONFLICT"),
            mapping(SeasonEndedException.class, HttpStatus.CONFLICT, "SEASON_ENDED"),
            mapping(SeasonSuccessorExistsException.class, HttpStatus.CONFLICT, "SEASON_SUCCESSOR_EXISTS"),
            mapping(SeasonRoundNameConflictException.class, HttpStatus.CONFLICT, "ROUND_NAME_CONFLICT"),
            mapping(IdempotencyKeyReusedException.class, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED"),
            mapping(IdempotencyKeyConflictException.class, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT"),
            mapping(IdempotencyReplayExpiredException.class, HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY_EXPIRED"),
            mapping(WatchHealthEventIdMismatchException.class, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISMATCH"),
            mapping(WatchHealthEventResourceReferenceException.class, HttpStatus.BAD_REQUEST,
                    "WATCH_RESOURCE_REFERENCE_INVALID"),
            mapping(WatchHealthEventChangedAtOutOfRangeException.class, HttpStatus.BAD_REQUEST, "INVALID_INPUT"),
            mapping(WatchHealthEventConflictException.class, HttpStatus.CONFLICT, "WATCH_EVENT_ID_CONFLICT"),
            mapping(DomainValidationException.class, HttpStatus.BAD_REQUEST, "INVALID_INPUT")
    );

    private static Map.Entry<Class<? extends RuntimeException>, ErrorMapping> mapping(
            Class<? extends RuntimeException> type,
            HttpStatus status,
            String code
    ) {
        return Map.entry(type, new ErrorMapping(status, code));
    }

    // 처리기 하나가 모든 런타임 예외를 받고, 표에 없는 예외는 내부 오류로 숨긴다.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> handleApplicationException(RuntimeException exception, WebRequest request) {
        for (Class<?> type = exception.getClass(); type != RuntimeException.class; type = type.getSuperclass()) {
            ErrorMapping mapping = ERROR_MAPPINGS.get(type);
            if (mapping != null) {
                HttpObservationErrors.mark(servletRequest(request), exception);
                return ResponseEntity.status(mapping.status())
                        .body(new ErrorResponse(mapping.code(), exception.getMessage()));
            }
        }
        return handleUnexpected(exception, request);
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(
            WorkspaceNotFoundException exception,
            HttpServletRequest request
    ) {
        HttpObservationErrors.mark(request, exception);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

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
