package com.personal.baton.adapter.in.web;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.error.LinkGatewayConflictException;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.error.InactiveMemberIdentityException;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
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
import org.springframework.http.CacheControl;
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

    @ExceptionHandler(RoleHandoffStateConflictException.class)
    public ResponseEntity<ErrorResponse> handleRoleHandoffStateConflict(
            RoleHandoffStateConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ROLE_HANDOFF_STATE_CONFLICT",
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(RoleHandoffTransitionException.class)
    public ResponseEntity<ErrorResponse> handleRoleHandoffTransition(
            RoleHandoffTransitionException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "ROLE_HANDOFF_STATE_CONFLICT",
                exception.getMessage(),
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
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(SeasonNameConflictException.class)
    public ResponseEntity<ErrorResponse> handleSeasonNameConflict(
            SeasonNameConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "SEASON_NAME_CONFLICT", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(SeasonEndedException.class)
    public ResponseEntity<ErrorResponse> handleSeasonEnded(
            SeasonEndedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "SEASON_ENDED", exception.getMessage(), exception, request);
    }

    @ExceptionHandler(SeasonSuccessorExistsException.class)
    public ResponseEntity<ErrorResponse> handleSeasonSuccessorExists(
            SeasonSuccessorExistsException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "SEASON_SUCCESSOR_EXISTS",
                exception.getMessage(),
                exception,
                request
        );
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

    @ExceptionHandler(InvalidLinkIntentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLinkIntent(
            InvalidLinkIntentException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                exception.getCode(),
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(LinkGatewayConflictException.class)
    public ResponseEntity<ErrorResponse> handleLinkGatewayConflict(
            LinkGatewayConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                LinkGatewayConflictException.CODE,
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(LinkGatewayUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleLinkGatewayUnavailable(
            LinkGatewayUnavailableException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_GATEWAY,
                LinkGatewayUnavailableException.CODE,
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(IdentityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIdentityNotFound(
            IdentityNotFoundException exception,
            HttpServletRequest request
    ) {
        return identityError(
                HttpStatus.NOT_FOUND,
                exception.getCode(),
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(MemberIdentityConflictException.class)
    public ResponseEntity<ErrorResponse> handleMemberIdentityConflict(
            MemberIdentityConflictException exception,
            HttpServletRequest request
    ) {
        return identityError(
                HttpStatus.CONFLICT,
                "MEMBER_IDENTITY_CONFLICT",
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(InactiveMemberIdentityException.class)
    public ResponseEntity<ErrorResponse> handleInactiveMemberIdentity(
            InactiveMemberIdentityException exception,
            HttpServletRequest request
    ) {
        return identityError(
                HttpStatus.CONFLICT,
                "INACTIVE_MEMBER_IDENTITY",
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(IdentityOperationException.class)
    public ResponseEntity<ErrorResponse> handleIdentityOperation(
            IdentityOperationException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.getCode()) {
            case "INVALID_INPUT",
                    "INVALID_IDEMPOTENCY_KEY",
                    "INVALID_EXTERNAL_IDENTITY" -> HttpStatus.BAD_REQUEST;
            case "BOOTSTRAP_INVITATION_FORBIDDEN",
                    "MEMBER_INVITATION_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "BOOTSTRAP_INVITATION_NOT_FOUND",
                    "MEMBER_INVITATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BOOTSTRAP_INVITATION_EXPIRED",
                    "BOOTSTRAP_INVITATION_REVOKED",
                    "MEMBER_INVITATION_EXPIRED",
                    "MEMBER_INVITATION_REVOKED" -> HttpStatus.GONE;
            case "BOOTSTRAP_IDEMPOTENCY_KEY_REUSED",
                    "BOOTSTRAP_INVITATION_CONFLICT",
                    "BOOTSTRAP_TARGET_UNAVAILABLE",
                    "BOOTSTRAP_MEMBER_INACTIVE",
                    "BOOTSTRAP_OWNER_EXISTS",
                    "BOOTSTRAP_INVITATION_USED",
                    "MEMBER_INVITATION_IDEMPOTENCY_KEY_REUSED",
                    "MEMBER_INVITATION_TARGET_UNAVAILABLE",
                    "MEMBER_INVITATION_USED",
                    "MEMBER_INVITATION_CONFLICT",
                    "EXTERNAL_IDENTITY_CONFLICT" -> HttpStatus.CONFLICT;
            case "BOOTSTRAP_CONFIGURATION_INVALID",
                    "MEMBER_INVITATION_CONFIGURATION_INVALID" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return identityError(
                status,
                exception.getCode(),
                exception.getMessage(),
                exception,
                request
        );
    }

    @ExceptionHandler(RoundGrantOperationException.class)
    public ResponseEntity<ErrorResponse> handleRoundGrantOperation(
            RoundGrantOperationException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.getCode()) {
            case "ROUND_GRANT_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "ROUND_RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ROUND_RESOURCE_NOT_ELIGIBLE",
                    "ROUND_RESOURCE_AMBIGUOUS" -> HttpStatus.CONFLICT;
            case "ROUND_GRANT_SIGNER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return identityError(
                status,
                exception.getCode(),
                exception.getMessage(),
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

    private ResponseEntity<ErrorResponse> identityError(
            HttpStatus status,
            String code,
            String message,
            Exception exception,
            HttpServletRequest request
    ) {
        markObservationError(request, exception);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(code, message));
    }

    private ResponseEntity<Object> mvcError(
            HttpStatusCode status,
            HttpHeaders headers,
            ErrorResponse response,
            Exception exception,
            WebRequest request
    ) {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        if (isIdentitySessionRequest(servletRequest(request))) {
            responseHeaders.set(
                    HttpHeaders.CACHE_CONTROL,
                    CacheControl.noStore().getHeaderValue()
            );
        }
        return handleExceptionInternal(
                exception,
                response,
                responseHeaders,
                status,
                request
        );
    }

    private boolean isIdentitySessionRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/identity/")
                || path.equals("/api/v1/auth/session")
                || path.equals("/api/v1/me")
                || path.equals("/api/v1/session/logout")
                || path.equals("/.well-known/jwks.json")
                || path.matches(
                        "^/api/v1/teams/[^/]+/seasons/[^/]+"
                                + "/role-resources/[^/]+/round-participation-grant$"
                )
                || path.matches(
                        "^/api/v1/round/rooms/[^/]+/participation-grant$"
                )
                || path.matches(
                        "^/api/v1/teams/[^/]+/"
                                + "(membership|member-invitations"
                                + "(/[^/]+/revocation)?)$"
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
