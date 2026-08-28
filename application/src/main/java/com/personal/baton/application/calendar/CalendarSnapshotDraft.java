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

    public CalendarSnapshot numbered(int revision) {
        return new CalendarSnapshot(
                eventId,
                occurredAt,
                sourceItemId,
                seasonId,
                revision,
                status,
                summary,
                description,
                location,
                time,
                sourceUpdatedAt
        );
    }
}
