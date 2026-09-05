package com.personal.baton.adapter.in.web.roundauth;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.IssueParticipationGrantCommand;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase.RoundRoomHint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ParticipationGrantController {

    public static final String REFRESH_PATH_PATTERN =
            "/round/rooms/{roomId}/participation-grant/refresh";
    public static final String JWK_SET_PATH =
            "/.well-known/round-participation-jwks.json";

    private static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final MediaType JWK_SET_MEDIA_TYPE =
            MediaType.parseMediaType("application/jwk-set+json");

    private final RoundParticipationUseCase roundParticipationUseCase;
    private final Clock clock;

    public ParticipationGrantController(
            RoundParticipationUseCase roundParticipationUseCase,
            Clock clock
    ) {
        this.roundParticipationUseCase = roundParticipationUseCase;
        this.clock = clock;
    }

    @PostMapping(REFRESH_PATH_PATTERN)
    public ResponseEntity<ParticipationGrantResponse> refresh(
            @PathVariable String roomId,
            @Valid @RequestBody(required = false) ParticipationGrantRequest body,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal,
            HttpServletRequest request
    ) {
        if (body == null && request.getContentType() != null) {
            throw new IllegalArgumentException("hint가 없으면 Content-Type과 요청 본문을 보내지 않아야 합니다");
        }
        var result = roundParticipationUseCase.issueParticipationGrant(
                new IssueParticipationGrantCommand(
                        principal.accountId(), roomId, body == null ? null : body.toHint()
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        RoundGrantCookie.issue(
                                result.roomId(),
                                result.token(),
                                result.expiresAt(),
                                clock
                        ).toString()
                )
                .body(new ParticipationGrantResponse(
                        result.expiresAt(),
                        result.refreshAfterSeconds()
                ));
    }

    @GetMapping(JWK_SET_PATH)
    public ResponseEntity<String> publicJwkSet() {
        return ResponseEntity.ok()
                .contentType(JWK_SET_MEDIA_TYPE)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(roundParticipationUseCase.readPublicJwkSetJson());
    }

    public record ParticipationGrantRequest(
            @NotNull @Pattern(regexp = CANONICAL_UUID) String teamId,
            @NotNull @Pattern(regexp = CANONICAL_UUID) String seasonId,
            @NotNull @Pattern(regexp = CANONICAL_UUID) String resourceId
    ) {
        RoundRoomHint toHint() {
            return new RoundRoomHint(
                    UUID.fromString(teamId), UUID.fromString(seasonId), UUID.fromString(resourceId)
            );
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignoredValue) {
            throw new IllegalArgumentException("ROUND room hint는 teamId, seasonId, resourceId만 포함해야 합니다");
        }
    }

    public record ParticipationGrantResponse(
            long expiresAt,
            int refreshAfterSeconds
    ) {
    }
}
