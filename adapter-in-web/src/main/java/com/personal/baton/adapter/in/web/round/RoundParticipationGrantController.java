package com.personal.baton.adapter.in.web.round;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoundParticipationGrantController {

    private static final String REQUIRED_COOKIE_NAME = "__Secure-round_access";

    private final RoundParticipationGrantUseCase useCase;
    private final String cookieName;

    public RoundParticipationGrantController(
            RoundParticipationGrantUseCase useCase,
            @Value("${baton.round.grant.cookie-name:__Secure-round_access}")
            String cookieName
    ) {
        this.useCase = useCase;
        if (!REQUIRED_COOKIE_NAME.equals(cookieName)) {
            throw new IllegalStateException(
                    "ROUND participation cookie 이름은 __Secure-round_access여야 합니다"
            );
        }
        this.cookieName = cookieName;
    }

    @PostMapping(
            "/api/v1/teams/{teamId}/seasons/{seasonId}"
                    + "/role-resources/{resourceId}/round-participation-grant"
    )
    public ResponseEntity<Void> issueForResource(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID resourceId,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        IssuedRoundParticipationGrant grant = useCase.issue(
                teamId,
                seasonId,
                resourceId,
                new AuthenticatedAccount(principal.accountId())
        );
        return grantResponse(grant);
    }

    @PostMapping("/api/v1/round/rooms/{roomId}/participation-grant")
    public ResponseEntity<Void> issueForRoom(
            @PathVariable String roomId,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        IssuedRoundParticipationGrant grant = useCase.issueForRoom(
                roomId,
                new AuthenticatedAccount(principal.accountId())
        );
        return grantResponse(grant);
    }

    @PostMapping("/round/rooms/{roomId}/participation-grant/refresh")
    public ResponseEntity<RoundParticipationGrantRefreshResponse> refreshForRoom(
            @PathVariable String roomId,
            @Valid @RequestBody(required = false)
            RoundParticipationGrantRefreshRequest request,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        AuthenticatedAccount account = new AuthenticatedAccount(principal.accountId());
        IssuedRoundParticipationGrant grant = request == null
                ? useCase.issueForRoom(roomId, account)
                : useCase.issueForRoom(
                        roomId,
                        request.teamId(),
                        request.seasonId(),
                        request.resourceId(),
                        account
                );
        return refreshResponse(grant);
    }

    private ResponseEntity<Void> grantResponse(
            IssuedRoundParticipationGrant grant
    ) {
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, grantCookie(grant).toString())
                .build();
    }

    private ResponseEntity<RoundParticipationGrantRefreshResponse> refreshResponse(
            IssuedRoundParticipationGrant grant
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, grantCookie(grant).toString())
                .body(RoundParticipationGrantRefreshResponse.from(grant));
    }

    private ResponseCookie grantCookie(IssuedRoundParticipationGrant grant) {
        return ResponseCookie.from(cookieName, grant.token())
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/round/rooms/" + grant.roomId())
                .maxAge(grant.maxAgeSeconds())
                .build();
    }
}
