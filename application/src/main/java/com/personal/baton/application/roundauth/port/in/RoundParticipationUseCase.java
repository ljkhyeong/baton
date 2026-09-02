package com.personal.baton.application.roundauth.port.in;

import java.util.UUID;

public interface RoundParticipationUseCase {

    ParticipationGrantResult issueParticipationGrant(IssueParticipationGrantCommand command);

    String readPublicJwkSetJson();

    record IssueParticipationGrantCommand(
            UUID accountId,
            String roomId,
            RoundRoomHint hint
    ) {
    }

    record RoundRoomHint(UUID teamId, UUID seasonId, UUID resourceId) {
    }

    record ParticipationGrantResult(
            String token,
            long expiresAt,
            int refreshAfterSeconds,
            String roomId
    ) {
    }
}
