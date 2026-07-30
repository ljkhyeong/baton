package com.personal.baton.application.round.port.in;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface RoundParticipationGrantUseCase {

    IssuedRoundParticipationGrant issue(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            AuthenticatedAccount authenticatedAccount
    );

    IssuedRoundParticipationGrant issueForRoom(
            String roomId,
            AuthenticatedAccount authenticatedAccount
    );

    PublicRoundJwkSet getPublicJwkSet();

    final class IssuedRoundParticipationGrant {

        private final String token;
        private final String roomId;
        private final Instant issuedAt;
        private final Instant expiresAt;
        private final long maxAgeSeconds;

        public IssuedRoundParticipationGrant(
                String token,
                String roomId,
                Instant issuedAt,
                Instant expiresAt,
                long maxAgeSeconds
        ) {
            this.token = Objects.requireNonNull(token);
            this.roomId = Objects.requireNonNull(roomId);
            this.issuedAt = Objects.requireNonNull(issuedAt);
            this.expiresAt = Objects.requireNonNull(expiresAt);
            this.maxAgeSeconds = maxAgeSeconds;
        }

        public String token() {
            return token;
        }

        public String roomId() {
            return roomId;
        }

        public Instant issuedAt() {
            return issuedAt;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public long maxAgeSeconds() {
            return maxAgeSeconds;
        }

        @Override
        public String toString() {
            return "IssuedRoundParticipationGrant[redacted]";
        }
    }

    record PublicRoundJwkSet(String body, String etag) {

        public PublicRoundJwkSet {
            Objects.requireNonNull(body);
            Objects.requireNonNull(etag);
        }
    }
}
