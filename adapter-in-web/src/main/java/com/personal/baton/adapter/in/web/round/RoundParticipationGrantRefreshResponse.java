package com.personal.baton.adapter.in.web.round;

import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;

public record RoundParticipationGrantRefreshResponse(
        long expiresAt,
        long refreshAfterSeconds
) {

    static RoundParticipationGrantRefreshResponse from(
            IssuedRoundParticipationGrant grant
    ) {
        return new RoundParticipationGrantRefreshResponse(
                grant.expiresAt().getEpochSecond(),
                grant.refreshAfterSeconds()
        );
    }
}
