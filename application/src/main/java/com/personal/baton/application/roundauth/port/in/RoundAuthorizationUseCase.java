package com.personal.baton.application.roundauth.port.in;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RoundAuthorizationUseCase {

    Optional<MembershipResult> findCurrentMembership(CurrentMembershipQuery query);

    Optional<RoomMappingResult> findCurrentRoomMapping(CurrentRoomMappingQuery query);

    MembershipResult claimMembership(ClaimMembershipCommand command);

    RoomMappingResult createRoomMapping(CreateRoomMappingCommand command);

    RoomMappingResult endRoomMapping(EndRoomMappingCommand command);

    ParticipationGrantResult issueParticipationGrant(IssueParticipationGrantCommand command);

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

    record CurrentRoomMappingQuery(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String workspaceAccessKey
    ) {
    }

    record EndRoomMappingCommand(
            UUID accountId,
            String roomId,
            String workspaceAccessKey
    ) {
    }

    record IssueParticipationGrantCommand(
            UUID accountId,
            String roomId,
            RoundRoomHint hint
    ) {
    }

    record RoundRoomHint(UUID teamId, UUID seasonId, UUID resourceId) {
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

    record ParticipationGrantResult(
            String token,
            long expiresAt,
            int refreshAfterSeconds,
            String roomId
    ) {
    }
}
