package com.personal.baton.application.relay;

import java.util.Objects;
import java.util.UUID;

public record RelayOutboxPublication(
        long rowId,
        RelayOutboxEvent event,
        int attemptCount,
        UUID leaseToken
) {

    public RelayOutboxPublication {
        if (rowId < 1) {
            throw new IllegalArgumentException("RELAY outbox rowId는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(event, "RELAY outbox event는 필수입니다");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("RELAY attemptCount는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(leaseToken, "RELAY leaseToken은 필수입니다");
    }
}
