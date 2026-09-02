package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledRoundGenerationWorker {

    private static final int MAX_AUTO_NAME_ATTEMPTS = 1_000;

    private final WorkspaceAccessRepository accessRepository;
    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspaceOperationsRepository operationsRepository;
    private final RoutineExecutionSnapshotFactory snapshotFactory;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;
    private final CalendarChangeRecorder calendarChangeRecorder;

    public ScheduledRoundGenerationWorker(
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository,
            WorkspaceOperationsRepository operationsRepository,
            RoutineExecutionSnapshotFactory snapshotFactory,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        this.accessRepository = accessRepository;
        this.seasonRepository = seasonRepository;
        this.operationsRepository = operationsRepository;
        this.snapshotFactory = snapshotFactory;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
        this.calendarChangeRecorder = calendarChangeRecorder;
    }

    @Transactional
    public boolean generateNextOccurrence(
            ScheduledSeasonCandidate candidate,
            Instant triggeredAt
    ) {
        if (accessRepository.findTeamByIdWithSharedLock(candidate.teamId()).isEmpty()) {
            return false;
        }
        Season season = seasonRepository.findSeasonByTeamIdAndIdForUpdate(
                        candidate.teamId(),
                        candidate.seasonId()
                )
                .orElse(null);
        if (season == null || season.isEnded()) {
            return false;
        }

        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule == null || !schedule.isEnabled()) {
            return false;
        }
        if (schedule.getNextOccurrenceDate().isAfter(season.getEndDate())) {
            season.disableRoundSchedule();
            seasonRepository.saveSeason(season);
            return false;
        }

        ZoneId zoneId = season.getZoneId();
        LocalDate today = LocalDate.ofInstant(triggeredAt, zoneId);
        LocalDate occurrenceDate = season.nextDueRoundOccurrence(today).orElse(null);
        if (occurrenceDate == null) {
            return false;
        }
        if (!season.contains(occurrenceDate)) {
            season.disableRoundSchedule();
            seasonRepository.saveSeason(season);
            return false;
        }

        boolean alreadyGenerated =
                operationsRepository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                        season.getId(),
                        occurrenceDate
                );
        boolean created = !alreadyGenerated && createAutomaticRound(season, schedule, occurrenceDate);
        season.advanceRoundSchedule();
        seasonRepository.saveSeason(season);
        if (created) {
            briefContinuitySignalRecorder.reconcileSeason(candidate.teamId(), candidate.seasonId());
        }
        return true;
    }

    private boolean createAutomaticRound(
            Season season,
            RoundSchedule schedule,
            LocalDate occurrenceDate
    ) {
        List<Routine> routines = operationsRepository.findRoutinesBySeasonId(season.getId()).stream()
                .filter(routine -> routine.getArchivedAt() == null)
                .toList();
        if (routines.isEmpty()) {
            return false;
        }
        for (Routine routine : routines) {
            if (routine.getDeadlineDayOffset() == null || routine.getDeadlineTime() == null) {
                throw new IllegalStateException("자동 회차 루틴에 실제 마감 규칙이 없습니다");
            }
        }

        String name = availableAutomaticRoundName(season.getId(), occurrenceDate);
        Instant scheduledAt = occurrenceDate
                .atTime(schedule.getMeetingTime())
                .atZone(season.getZoneId())
                .toInstant();
        SeasonRound round = SeasonRound.createAutomatic(
                UUID.randomUUID(),
                season.getId(),
                name,
                occurrenceDate,
                scheduledAt
        );
        List<RoutineExecution> executions = snapshotFactory.snapshotAll(
                round.getId(),
                routines,
                occurrenceDate,
                season.getZoneId()
        );
        SeasonRound savedRound = operationsRepository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = operationsRepository.saveRoutineExecutions(executions);
        calendarChangeRecorder.record(season, savedRound, savedExecutions);
        return true;
    }

    private String availableAutomaticRoundName(UUID seasonId, LocalDate occurrenceDate) {
        String baseName = "자동 회차 " + occurrenceDate;
        if (!operationsRepository.existsSeasonRoundBySeasonIdAndName(seasonId, baseName)) {
            return baseName;
        }
        for (int sequence = 2; sequence <= MAX_AUTO_NAME_ATTEMPTS; sequence++) {
            String candidate = baseName + " #" + sequence;
            if (!operationsRepository.existsSeasonRoundBySeasonIdAndName(seasonId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("자동 회차 이름을 예약할 수 없습니다");
    }
}
