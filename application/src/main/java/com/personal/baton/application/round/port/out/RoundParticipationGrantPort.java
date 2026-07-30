package com.personal.baton.application.round.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface RoundParticipationGrantPort {

    SignedParticipationGrant issueParticipantGrant(ParticipantGrantCommand command);

    String canonicalResourceUrl(String roomId);

    PublicJwkSet loadPublicJwkSet();

    record ParticipantGrantCommand(
            UUID accountId,
            UUID seasonId,
            String resourceUrl,
            Instant issuedAt
    ) {

        public ParticipantGrantCommand {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(seasonId);
            Objects.requireNonNull(resourceUrl);
            Objects.requireNonNull(issuedAt);
        }

        @Override
        public String toString() {
            return "ParticipantGrantCommand[redacted]";
        }
    }

    final class SignedParticipationGrant {

        private final String token;
        private final String roomId;
        private final Instant issuedAt;
        private final Instant expiresAt;

        public SignedParticipationGrant(
                String token,
                String roomId,
                Instant issuedAt,
                Instant expiresAt
        ) {
            this.token = Objects.requireNonNull(token);
            this.roomId = Objects.requireNonNull(roomId);
            this.issuedAt = Objects.requireNonNull(issuedAt);
            this.expiresAt = Objects.requireNonNull(expiresAt);
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

        @Override
        public String toString() {
            return "SignedParticipationGrant[redacted]";
        }
    }

    record PublicJwkSet(String body, String etag) {

        public PublicJwkSet {
            Objects.requireNonNull(body);
            Objects.requireNonNull(etag);
        }
    }
}
