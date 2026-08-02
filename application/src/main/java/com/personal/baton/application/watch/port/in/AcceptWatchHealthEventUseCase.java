package com.personal.baton.application.watch.port.in;

import com.personal.baton.application.watch.WatchResourceHealth;
import java.time.Instant;
import java.util.UUID;

public interface AcceptWatchHealthEventUseCase {

    WatchHealthEventReceipt accept(
            UUID idempotencyKey,
            AcceptWatchHealthEventCommand command
    );

    record AcceptWatchHealthEventCommand(
            UUID eventId,
            String eventType,
            String resourceReference,
            long sourceRevision,
            UUID attemptId,
            WatchResourceHealth previousHealth,
            WatchResourceHealth currentHealth,
            Instant changedAt
    ) {
    }

    record WatchHealthEventReceipt(
            UUID eventId,
            Instant acceptedAt
    ) {
    }
}
