package com.personal.baton.application.roundauth.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoundAdministrationUseCase {

    Optional<MembershipResult> findCurrentMembership(CurrentMembershipQuery query);

    List<RoomMappingResult> findCurrentRoomMappings(CurrentRoomMappingsQuery query);

    MembershipResult claimMembership(ClaimMembershipCommand command);

    RoomMappingResult createRoomMapping(CreateRoomMappingCommand command);

    RoomMappingResult endRoomMapping(EndRoomMappingCommand command);

    record CurrentMembershipQuery(
            UUID accountId,
            UUID teamId,
            String workspaceAccessKey
    ) {
    }

    record ClaimMembershipCommand(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String workspaceAccessKey
    ) {
    }

    record CreateRoomMappingCommand(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String workspaceAccessKey
    ) {
    }

    record CurrentRoomMappingsQuery(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            String workspaceAccessKey
    ) {
    }

    record EndRoomMappingCommand(
            UUID accountId,
            String roomId,
            String workspaceAccessKey
    ) {
    }

    record MembershipResult(
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant claimedAt
    ) {
    }

    record RoomMappingResult(
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt,
            Instant endedAt
    ) {
    }
}
