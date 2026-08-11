package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.MembershipResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class RoundAdministrationResponses {

    private RoundAdministrationResponses() {
    }

    public sealed interface CurrentMembershipResponse permits
            ClaimedCurrentMembershipResponse,
            UnclaimedCurrentMembershipResponse {

        static CurrentMembershipResponse from(Optional<MembershipResult> membership) {
            return membership
                    .<CurrentMembershipResponse>map(ClaimedCurrentMembershipResponse::from)
                    .orElseGet(UnclaimedCurrentMembershipResponse::new);
        }
    }

    public record ClaimedCurrentMembershipResponse(
            boolean claimed,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant claimedAt
    ) implements CurrentMembershipResponse {

        private ClaimedCurrentMembershipResponse(
                UUID accountId,
                UUID teamId,
                UUID memberId,
                Instant claimedAt
        ) {
            this(true, accountId, teamId, memberId, claimedAt);
        }

        static ClaimedCurrentMembershipResponse from(MembershipResult result) {
            return new ClaimedCurrentMembershipResponse(
                    result.accountId(),
                    result.teamId(),
                    result.memberId(),
                    result.claimedAt()
            );
        }
    }

    public record UnclaimedCurrentMembershipResponse(
            boolean claimed
    ) implements CurrentMembershipResponse {

        private UnclaimedCurrentMembershipResponse() {
            this(false);
        }
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

    public sealed interface CurrentRoomMappingResponse permits
            MappedCurrentRoomMappingResponse,
            UnmappedCurrentRoomMappingResponse {

        static CurrentRoomMappingResponse from(Optional<RoomMappingResult> mapping) {
            return mapping
                    .<CurrentRoomMappingResponse>map(MappedCurrentRoomMappingResponse::from)
                    .orElseGet(UnmappedCurrentRoomMappingResponse::new);
        }
    }

    public record MappedCurrentRoomMappingResponse(
            boolean mapped,
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt,
            Instant endedAt
    ) implements CurrentRoomMappingResponse {

        private MappedCurrentRoomMappingResponse(
                String roomId,
                UUID teamId,
                UUID seasonId,
                UUID resourceId,
                Instant createdAt,
                Instant endedAt
        ) {
            this(true, roomId, teamId, seasonId, resourceId, createdAt, endedAt);
        }

        static MappedCurrentRoomMappingResponse from(RoomMappingResult result) {
            return new MappedCurrentRoomMappingResponse(
                    result.roomId(),
                    result.teamId(),
                    result.seasonId(),
                    result.resourceId(),
                    result.createdAt(),
                    result.endedAt()
            );
        }
    }

    public record UnmappedCurrentRoomMappingResponse(
            boolean mapped
    ) implements CurrentRoomMappingResponse {

        private UnmappedCurrentRoomMappingResponse() {
            this(false);
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
