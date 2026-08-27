package com.personal.baton.application.calendar;

import java.time.Instant;
import java.util.UUID;

public record CalendarSnapshotDraft(
        UUID eventId,
        Instant occurredAt,
        UUID sourceItemId,
        UUID seasonId,
        CalendarSnapshot.Status status,
        String summary,
        String description,
        String location,
        CalendarSnapshot.Time time,
        Instant sourceUpdatedAt
) {
}
