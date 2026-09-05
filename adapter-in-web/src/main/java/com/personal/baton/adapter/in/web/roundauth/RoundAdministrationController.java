package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationRequests.CreateRoomMappingRequest;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationRequests.MembershipClaimRequest;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.CurrentMembershipResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.CurrentRoomMappingsResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.MembershipClaimResponse;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationResponses.RoomMappingResponse;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.CurrentMembershipQuery;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.CurrentRoomMappingsQuery;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.EndRoomMappingCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final RoundAdministrationUseCase roundAdministrationUseCase;

    public RoundAdministrationController(RoundAdministrationUseCase roundAdministrationUseCase) {
        this.roundAdministrationUseCase = roundAdministrationUseCase;
    }

    @GetMapping(CURRENT_MEMBERSHIP_PATH)
    public ResponseEntity<CurrentMembershipResponse> getCurrentMembership(
            @RequestParam UUID teamId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = roundAdministrationUseCase.findCurrentMembership(
                new CurrentMembershipQuery(principal.accountId(), teamId, accessKey)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentMembershipResponse.from(result));
    }

    @PostMapping(MEMBERSHIP_CLAIMS_PATH)
    public ResponseEntity<MembershipClaimResponse> claimMembership(
            @Valid @RequestBody MembershipClaimRequest request,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        if (!principal.accountId().equals(request.expectedAccountId())) {
            throw new AccountMembershipConflictException(
                    "로그인 계정이 변경되었습니다. 새로고침한 뒤 연결할 계정을 다시 확인해 주세요."
            );
        }
        var result = roundAdministrationUseCase.claimMembership(
                new ClaimMembershipCommand(
                        principal.accountId(),
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
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = roundAdministrationUseCase.createRoomMapping(
                new CreateRoomMappingCommand(
                        principal.accountId(),
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
    public ResponseEntity<CurrentRoomMappingsResponse> getCurrentRoomMappings(
            @RequestParam UUID teamId,
            @RequestParam UUID seasonId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = roundAdministrationUseCase.findCurrentRoomMappings(
                new CurrentRoomMappingsQuery(
                        principal.accountId(),
                        teamId,
                        seasonId,
                        accessKey
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentRoomMappingsResponse.from(result));
    }

    @DeleteMapping(ROOM_MAPPING_PATH_PATTERN)
    public ResponseEntity<RoomMappingResponse> endRoomMapping(
            @PathVariable String roomId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = roundAdministrationUseCase.endRoomMapping(
                new EndRoomMappingCommand(principal.accountId(), roomId, accessKey)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RoomMappingResponse.from(result));
    }

}
