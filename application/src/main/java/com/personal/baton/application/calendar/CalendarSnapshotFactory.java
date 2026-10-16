package com.personal.baton.application.calendar;

import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class CalendarSnapshotFactory {

    // 회차 스냅샷 다음에 마감이 있는 실행 스냅샷을 실행 목록 순서대로 둔다.
    public List<CalendarSnapshotDraft> roundSnapshots(
            Instant occurredAt,
            Season season,
            SeasonRound round,
            List<RoutineExecution> executions
    ) {
        List<CalendarSnapshotDraft> snapshots = new ArrayList<>(executions.size() + 1);
        snapshots.add(fromRound(UUID.randomUUID(), occurredAt, season, round));
        for (RoutineExecution execution : executions) {
            fromExecution(UUID.randomUUID(), occurredAt, round, execution).ifPresent(snapshots::add);
        }
        return snapshots;
    }

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
                !round.isArchived(),
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
                !round.isArchived(),
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
