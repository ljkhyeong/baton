package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record CalendarSnapshotRequest(
        UUID eventId,
        Instant occurredAt,
        UUID sourceItemId,
        UUID seasonId,
        int revision,
        CalendarSnapshot.Status status,
        String summary,
        String description,
        String location,
        TimeRequest time,
        Instant sourceUpdatedAt
) {

    public static CalendarSnapshotRequest from(CalendarSnapshot snapshot) {
        return new CalendarSnapshotRequest(
                snapshot.eventId(),
                snapshot.occurredAt(),
                snapshot.sourceItemId(),
                snapshot.seasonId(),
                snapshot.revision(),
                snapshot.status(),
                snapshot.summary(),
                snapshot.description(),
                snapshot.location(),
                TimeRequest.from(snapshot.time()),
                snapshot.sourceUpdatedAt()
        );
    }

    public sealed interface TimeRequest permits UtcPointRequest, ZonedLocalPointRequest, AllDayRequest {

        static TimeRequest from(CalendarSnapshot.Time time) {
            return switch (time) {
                case CalendarSnapshot.UtcPoint point -> new UtcPointRequest(
                        "UTC_POINT",
                        point.at()
                );
                case CalendarSnapshot.ZonedLocalPoint point -> new ZonedLocalPointRequest(
                        "ZONED_LOCAL_POINT",
                        point.zoneId(),
                        point.at()
                );
                case CalendarSnapshot.AllDay allDay -> new AllDayRequest(
                        "ALL_DAY",
                        allDay.startDate(),
                        allDay.endDate()
                );
            };
        }
    }

    public record UtcPointRequest(String type, Instant atInstant) implements TimeRequest {
    }

    public record ZonedLocalPointRequest(
            String type,
            String zoneId,
            LocalDateTime atLocal
    ) implements TimeRequest {
    }

    public record AllDayRequest(
            String type,
            LocalDate startDate,
            LocalDate endDate
    ) implements TimeRequest {
    }
}
