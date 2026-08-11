package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationRequests.CreateRoomMappingRequest;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationRequests.MembershipClaimRequest;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.CurrentMembershipResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.CurrentRoomMappingResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.MembershipClaimResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.RoomMappingResponse;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentMembershipQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentRoomMappingQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.EndRoomMappingCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoundAdministrationController {

    public static final String MEMBERSHIP_CLAIMS_PATH =
            "/api/v1/account-membership-claims";
    public static final String CURRENT_MEMBERSHIP_PATH =
            "/api/v1/account-memberships/current";
    public static final String ROOM_MAPPINGS_PATH =
            "/api/v1/round-room-mappings";
    public static final String ROOM_MAPPING_PATH_PATTERN =
            "/api/v1/round-room-mappings/{roomId}";

    private static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";

    private final RoundAuthorizationUseCase roundAuthorizationUseCase;

    public RoundAdministrationController(RoundAuthorizationUseCase roundAuthorizationUseCase) {
        this.roundAuthorizationUseCase = roundAuthorizationUseCase;
    }

    @GetMapping(CURRENT_MEMBERSHIP_PATH)
    public ResponseEntity<CurrentMembershipResponse> getCurrentMembership(
            @RequestParam UUID teamId,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
            Authentication authentication
    ) {
        var result = roundAuthorizationUseCase.findCurrentMembership(
                new CurrentMembershipQuery(accountId(authentication), teamId, accessKey)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentMembershipResponse.from(result));
    }

    @PostMapping(MEMBERSHIP_CLAIMS_PATH)
    public ResponseEntity<MembershipClaimResponse> claimMembership(
            @Valid @RequestBody MembershipClaimRequest request,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
            Authentication authentication
    ) {
        var result = roundAuthorizationUseCase.claimMembership(
                new ClaimMembershipCommand(
                        accountId(authentication),
                        request.teamId(),
                        request.seasonId(),
                        request.memberId(),
                        accessKey
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MembershipClaimResponse.from(result));
    }

    @PostMapping(ROOM_MAPPINGS_PATH)
    public ResponseEntity<RoomMappingResponse> createRoomMapping(
            @Valid @RequestBody CreateRoomMappingRequest request,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
            Authentication authentication
    ) {
        var result = roundAuthorizationUseCase.createRoomMapping(
                new CreateRoomMappingCommand(
                        accountId(authentication),
                        request.teamId(),
                        request.seasonId(),
                        request.resourceId(),
                        accessKey
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RoomMappingResponse.from(result));
    }

    @GetMapping(ROOM_MAPPINGS_PATH)
    public ResponseEntity<CurrentRoomMappingResponse> getCurrentRoomMapping(
            @RequestParam UUID teamId,
            @RequestParam UUID seasonId,
            @RequestParam UUID resourceId,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
            Authentication authentication
    ) {
        var result = roundAuthorizationUseCase.findCurrentRoomMapping(
                new CurrentRoomMappingQuery(
                        accountId(authentication),
                        teamId,
                        seasonId,
                        resourceId,
                        accessKey
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentRoomMappingResponse.from(result));
    }

    @DeleteMapping(ROOM_MAPPING_PATH_PATTERN)
    public ResponseEntity<RoomMappingResponse> endRoomMapping(
            @PathVariable String roomId,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
            Authentication authentication
    ) {
        var result = roundAuthorizationUseCase.endRoomMapping(
                new EndRoomMappingCommand(accountId(authentication), roomId, accessKey)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RoomMappingResponse.from(result));
    }

    private UUID accountId(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof AuthenticatedAccountPrincipal principal) {
            return principal.accountId();
        }
        throw new AccessDeniedException("인증된 계정이 필요합니다");
    }

}
