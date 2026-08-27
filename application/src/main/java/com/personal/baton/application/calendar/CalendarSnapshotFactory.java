package com.personal.baton.application.calendar;

import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class CalendarSnapshotFactory {

    public CalendarSnapshotDraft fromRound(
            UUID eventId,
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
                occurredAt,
                round.getId(),
                round.getSeasonId(),
                round.getArchivedAt() == null,
                round.getName(),
                null,
                time
        );
    }

    public Optional<CalendarSnapshotDraft> fromExecution(
            UUID eventId,
            Instant occurredAt,
            SeasonRound round,
            RoutineExecution execution
    ) {
        if (execution.getDeadlineAt() == null) {
            return Optional.empty();
        }
        return Optional.of(snapshot(
                eventId,
                occurredAt,
                execution.getId(),
                round.getSeasonId(),
                round.getArchivedAt() == null,
                execution.getTitle(),
                execution.getDetail(),
                new CalendarSnapshot.UtcPoint(execution.getDeadlineAt())
        ));
    }

    private CalendarSnapshotDraft snapshot(
            UUID eventId,
            Instant occurredAt,
            UUID sourceItemId,
            UUID seasonId,
            boolean active,
            String summary,
            String description,
            CalendarSnapshot.Time time
    ) {
        return new CalendarSnapshotDraft(
                eventId,
                occurredAt,
                sourceItemId,
                seasonId,
                active ? CalendarSnapshot.Status.ACTIVE : CalendarSnapshot.Status.CANCELLED,
                summary,
                description,
                null,
                time,
                occurredAt
        );
    }
}
