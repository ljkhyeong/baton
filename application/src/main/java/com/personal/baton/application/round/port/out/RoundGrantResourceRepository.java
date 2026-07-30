package com.personal.baton.application.round.port.out;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface RoundGrantResourceRepository {

    List<AuthorizedRoundResource> findAuthorizedResourcesByAccountIdAndUrl(
            UUID accountId,
            String resourceUrl
    );

    record AuthorizedRoundResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String resourceUrl
    ) {

        public AuthorizedRoundResource {
            Objects.requireNonNull(teamId);
            Objects.requireNonNull(seasonId);
            Objects.requireNonNull(resourceId);
            Objects.requireNonNull(resourceUrl);
        }

        @Override
        public String toString() {
            return "AuthorizedRoundResource[redacted]";
        }
    }
}
