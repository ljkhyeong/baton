package com.personal.baton.adapter.in.web.calendar;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import com.personal.baton.application.calendar.CalendarSubscriptionException;
import com.personal.baton.application.calendar.CalendarSubscriptionException.Reason;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Scope;
import com.personal.baton.adapter.in.web.calendar.CalendarSubscriptionResponses.SubscriptionResponse;
import com.personal.baton.adapter.in.web.calendar.CalendarSubscriptionResponses.CredentialResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarSubscriptionController {
    public static final String PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/calendar-subscription";
    public static final String ROTATE_PATH = PATH + "/rotate";
    private final CalendarSubscriptionUseCase subscriptions;

    public CalendarSubscriptionController(CalendarSubscriptionUseCase subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping(PATH)
    public ResponseEntity<SubscriptionResponse> find(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @RequestHeader("X-Baton-Account-Id") UUID expectedAccountId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        verifyAccount(principal, expectedAccountId);
        var result = subscriptions.find(new Scope(principal.accountId(), teamId, seasonId, accessKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SubscriptionResponse(result.subscriptionId(), result.seasonId(), result.status()));
    }

    @PostMapping(PATH)
    public ResponseEntity<CredentialResponse> create(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @RequestHeader("X-Baton-Account-Id") UUID expectedAccountId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        verifyAccount(principal, expectedAccountId);
        var result = subscriptions.create(new Scope(principal.accountId(), teamId, seasonId, accessKey));
        return credential(HttpStatus.CREATED, result);
    }

    @PostMapping(ROTATE_PATH)
    public ResponseEntity<CredentialResponse> rotate(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @RequestHeader("X-Baton-Account-Id") UUID expectedAccountId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        verifyAccount(principal, expectedAccountId);
        var result = subscriptions.rotate(new Scope(principal.accountId(), teamId, seasonId, accessKey));
        return credential(HttpStatus.OK, result);
    }

    @DeleteMapping(PATH)
    public ResponseEntity<Void> revoke(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @RequestHeader("X-Baton-Account-Id") UUID expectedAccountId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        verifyAccount(principal, expectedAccountId);
        subscriptions.revoke(new Scope(principal.accountId(), teamId, seasonId, accessKey));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void verifyAccount(AuthenticatedAccountPrincipal principal, UUID expectedAccountId) {
        if (!principal.accountId().equals(expectedAccountId)) {
            throw new CalendarSubscriptionException(Reason.ACCOUNT_CHANGED);
        }
    }

    private ResponseEntity<CredentialResponse> credential(HttpStatus status, CalendarSubscriptionUseCase.Credential result) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CredentialResponse(result.subscriptionId(), result.seasonId(), result.feedUrl()));
    }

}
