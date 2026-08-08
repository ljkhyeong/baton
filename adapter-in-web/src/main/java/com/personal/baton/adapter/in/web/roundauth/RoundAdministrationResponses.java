package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.MembershipResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import java.time.Instant;
import java.util.UUID;

public final class RoundAdministrationResponses {

    private RoundAdministrationResponses() {
    }

    public record MembershipClaimResponse(
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant claimedAt
    ) {

        public static MembershipClaimResponse from(MembershipResult result) {
            return new MembershipClaimResponse(
                    result.accountId(),
                    result.teamId(),
                    result.memberId(),
                    result.claimedAt()
            );
        }
    }

    public record RoomMappingResponse(
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt,
            Instant endedAt
    ) {

        public static RoomMappingResponse from(RoomMappingResult result) {
            return new RoomMappingResponse(
                    result.roomId(),
                    result.teamId(),
                    result.seasonId(),
                    result.resourceId(),
                    result.createdAt(),
                    result.endedAt()
            );
        }
    }
}
