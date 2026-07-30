package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository.ScheduledSeasonCandidate;
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

    private final WorkspaceRepository repository;

    public ScheduledRoundGenerationWorker(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean generateNextOccurrence(
            ScheduledSeasonCandidate candidate,
            Instant triggeredAt
    ) {
        if (repository.findTeamByIdWithSharedLock(candidate.teamId()).isEmpty()) {
            return false;
        }
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(
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
            repository.saveSeason(season);
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
            repository.saveSeason(season);
            return false;
        }

        boolean alreadyGenerated =
                repository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                        season.getId(),
                        occurrenceDate
                );
        if (!alreadyGenerated) {
            createAutomaticRound(season, schedule, occurrenceDate);
        }
        season.advanceRoundSchedule();
        repository.saveSeason(season);
        return true;
    }

    private void createAutomaticRound(
            Season season,
            RoundSchedule schedule,
            LocalDate occurrenceDate
    ) {
        List<Routine> routines = repository.findRoutinesBySeasonId(season.getId());
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
        List<RoutineExecution> executions = routines.stream()
                .map(routine -> RoutineExecution.snapshot(
                        UUID.randomUUID(),
                        round.getId(),
                        routine,
                        occurrenceDate,
                        season.getZoneId()
                ))
                .toList();
        repository.saveSeasonRound(round);
        repository.saveRoutineExecutions(executions);
    }

    private String availableAutomaticRoundName(UUID seasonId, LocalDate occurrenceDate) {
        String baseName = "자동 회차 " + occurrenceDate;
        if (!repository.existsSeasonRoundBySeasonIdAndName(seasonId, baseName)) {
            return baseName;
        }
        for (int sequence = 2; sequence <= MAX_AUTO_NAME_ATTEMPTS; sequence++) {
            String candidate = baseName + " #" + sequence;
            if (!repository.existsSeasonRoundBySeasonIdAndName(seasonId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("자동 회차 이름을 예약할 수 없습니다");
    }
}
