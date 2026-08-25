package com.personal.baton.application.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record CalendarSnapshot(
        UUID eventId,
        Instant occurredAt,
        UUID sourceItemId,
        UUID seasonId,
        int revision,
        Status status,
        String summary,
        String description,
        String location,
        Time time,
        Instant sourceUpdatedAt
) {

    public enum Status {
        ACTIVE,
        CANCELLED
    }

    public sealed interface Time permits UtcPoint, ZonedLocalPoint, AllDay {
    }

    public record UtcPoint(Instant at) implements Time {
    }

    public record ZonedLocalPoint(LocalDateTime at, String zoneId) implements Time {
    }

    public record AllDay(LocalDate startDate, LocalDate endDate) implements Time {
    }
}
