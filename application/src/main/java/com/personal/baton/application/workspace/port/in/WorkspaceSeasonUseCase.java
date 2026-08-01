package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.RoundRecurrence;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceSeasonUseCase {

    SeasonResult updateSeasonAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateSeasonCommand command
    );

    @Transactional
    default SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateSeasonCommand command
    ) {
        return updateSeasonAuthorized(teamId, seasonId, legacy(accessKey), command);
    }

    SeasonResult updateSeasonEndingAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            boolean ended
    );

    @Transactional
    default SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    ) {
        return updateSeasonEndingAuthorized(teamId, seasonId, legacy(accessKey), ended);
    }

    SeasonResult updateRoundScheduleAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateRoundScheduleCommand command
    );

    @Transactional
    default SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateRoundScheduleCommand command
    ) {
        return updateRoundScheduleAuthorized(teamId, seasonId, legacy(accessKey), command);
    }

    NextSeasonResult createNextSeasonAuthorized(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateNextSeasonCommand command
    );

    @Transactional
    default NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            CreateNextSeasonCommand command
    ) {
        return createNextSeasonAuthorized(
                teamId,
                sourceSeasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record UpdateSeasonCommand(String name, LocalDate startDate, LocalDate endDate) {
    }

    record UpdateRoundScheduleCommand(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled
    ) {
    }

    record CreateNextSeasonCommand(
            String name,
            LocalDate startDate,
            LocalDate endDate,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
    }

    record SeasonResult(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Instant endedAt,
            UUID previousSeasonId,
            String timeZone,
            RoundScheduleResult roundSchedule
    ) {
        public SeasonResult(
                UUID id,
                String name,
                LocalDate startDate,
                LocalDate endDate,
                Instant endedAt,
                UUID previousSeasonId
        ) {
            this(
                    id,
                    name,
                    startDate,
                    endDate,
                    endedAt,
                    previousSeasonId,
                    "Asia/Seoul",
                    null
            );
        }
    }

    record RoundScheduleResult(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
    }

    record NextSeasonResult(
            SeasonResult sourceSeason,
            SeasonResult season,
            List<CopiedRoleResult> copiedRoles,
            List<CopiedRoutineResult> copiedRoutines
    ) {
    }

    record CopiedRoleResult(UUID sourceRoleId, UUID roleId) {
    }

    record CopiedRoutineResult(UUID sourceRoutineId, UUID routineId) {
    }
}
