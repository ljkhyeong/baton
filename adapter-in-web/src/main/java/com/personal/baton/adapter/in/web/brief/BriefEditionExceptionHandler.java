package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.application.brief.error.BriefAccessDeniedException;
import com.personal.baton.application.brief.error.BriefAttentionQueryRejectedException;
import com.personal.baton.application.brief.error.BriefEditionNotFoundException;
import com.personal.baton.application.brief.error.BriefGenerationBlockedException;
import com.personal.baton.application.brief.error.BriefGenerationInProgressException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {BriefEditionController.class, BriefAttentionController.class, BriefWorkspaceContextController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BriefEditionExceptionHandler {

    @ExceptionHandler(BriefAttentionQueryRejectedException.class)
    public ResponseEntity<ErrorResponse> handleQueryRejected(BriefAttentionQueryRejectedException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception.getMessage());
    }

    @ExceptionHandler(BriefAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            BriefAccessDeniedException exception
    ) {
        return error(HttpStatus.FORBIDDEN, "BRIEF_ACCESS_DENIED", exception.getMessage());
    }

    @ExceptionHandler(BriefEditionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            BriefEditionNotFoundException exception
    ) {
        return error(HttpStatus.NOT_FOUND, "BRIEF_EDITION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(BriefGenerationBlockedException.class)
    public ResponseEntity<ErrorResponse> handleGenerationBlocked(
            BriefGenerationBlockedException exception
    ) {
        return error(HttpStatus.CONFLICT, "BRIEF_DELIVERY_INCOMPLETE", exception.getMessage());
    }

    @ExceptionHandler(BriefGenerationInProgressException.class)
    public ResponseEntity<ErrorResponse> handleGenerationInProgress(
            BriefGenerationInProgressException exception
    ) {
        return error(HttpStatus.CONFLICT, "BRIEF_GENERATION_IN_PROGRESS", exception.getMessage());
    }

    @ExceptionHandler(BriefIntegrationConfigurationException.class)
    public ResponseEntity<ErrorResponse> handleConfigurationError(
            BriefIntegrationConfigurationException exception
    ) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BRIEF_CONFIGURATION_ERROR", exception.getMessage());
    }

    @ExceptionHandler(BriefIntegrationUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(
            BriefIntegrationUnavailableException exception
    ) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BRIEF_UNAVAILABLE", exception.getMessage());
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
