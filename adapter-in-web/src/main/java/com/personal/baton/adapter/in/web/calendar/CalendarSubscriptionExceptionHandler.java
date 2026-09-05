package com.personal.baton.adapter.in.web.calendar;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.application.calendar.CalendarSubscriptionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CalendarSubscriptionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CalendarSubscriptionExceptionHandler {
    @ExceptionHandler(CalendarSubscriptionException.class)
    public ResponseEntity<ErrorResponse> handle(CalendarSubscriptionException exception) {
        HttpStatus status = switch (exception.reason()) {
            case ACCESS_DENIED, ACCOUNT_CHANGED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IN_PROGRESS, CREDENTIAL_REQUIRED -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse("CAL_SUBSCRIPTION_" + exception.reason().name(), exception.getMessage()));
    }
}
