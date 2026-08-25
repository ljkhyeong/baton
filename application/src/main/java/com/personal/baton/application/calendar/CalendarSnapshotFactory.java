package com.personal.baton.application.calendar;

import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class CalendarSnapshotFactory {

    public CalendarSnapshot fromRound(
            UUID eventId,
            int revision,
            Instant occurredAt,
            Season season,
            SeasonRound round
    ) {
        CalendarSnapshot.Time time = round.getOrigin() == RoundOrigin.AUTOMATIC
                ? new CalendarSnapshot.ZonedLocalPoint(
                        round.getScheduledAt().atZone(season.getZoneId()).toLocalDateTime(),
                        season.getTimeZone()
                )
                : new CalendarSnapshot.AllDay(
                        round.getMeetingDate(),
                        round.getMeetingDate().plusDays(1)
                );

        return snapshot(
                eventId,
                revision,
                occurredAt,
                round.getId(),
                round.getSeasonId(),
                round.getArchivedAt() == null,
                round.getName(),
                null,
                time
        );
    }

    public Optional<CalendarSnapshot> fromExecution(
            UUID eventId,
            int revision,
            Instant occurredAt,
            SeasonRound round,
            RoutineExecution execution
    ) {
        if (execution.getDeadlineAt() == null) {
            return Optional.empty();
        }
        return Optional.of(snapshot(
                eventId,
                revision,
                occurredAt,
                execution.getId(),
                round.getSeasonId(),
                round.getArchivedAt() == null,
                execution.getTitle(),
                execution.getDetail(),
                new CalendarSnapshot.UtcPoint(execution.getDeadlineAt())
        ));
    }

    private CalendarSnapshot snapshot(
            UUID eventId,
            int revision,
            Instant occurredAt,
            UUID sourceItemId,
            UUID seasonId,
            boolean active,
            String summary,
            String description,
            CalendarSnapshot.Time time
    ) {
        return new CalendarSnapshot(
                eventId,
                occurredAt,
                sourceItemId,
                seasonId,
                revision,
                active ? CalendarSnapshot.Status.ACTIVE : CalendarSnapshot.Status.CANCELLED,
                summary,
                description,
                null,
                time,
                occurredAt
        );
    }
}
