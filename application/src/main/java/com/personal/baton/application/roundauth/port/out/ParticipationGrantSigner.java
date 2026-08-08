package com.personal.baton.application.roundauth.port.out;

import java.time.Instant;
import java.util.UUID;

public interface ParticipationGrantSigner {

    String sign(ParticipationGrantClaims claims);

    record ParticipationGrantClaims(
            UUID accountId,
            UUID teamId,
            String roomId,
            UUID tokenId,
            String role,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
