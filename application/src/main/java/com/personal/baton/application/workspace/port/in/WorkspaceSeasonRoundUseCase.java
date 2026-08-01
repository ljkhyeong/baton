package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceSeasonRoundUseCase {

    SeasonRoundResult createSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateSeasonRoundCommand command
    );

    @Transactional
    default SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateSeasonRoundCommand command
    ) {
        return createSeasonRoundAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    SeasonRoundResult updateSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            UpdateSeasonRoundCommand command
    );

    @Transactional
    default SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            UpdateSeasonRoundCommand command
    ) {
        return updateSeasonRoundAuthorized(
                teamId,
                seasonId,
                roundId,
                legacy(accessKey),
                command
        );
    }

    SeasonRoundResult updateSeasonRoundArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    ) {
        return updateSeasonRoundArchiveAuthorized(
                teamId,
                seasonId,
                roundId,
                legacy(accessKey),
                archived
        );
    }

    RoutineExecutionResult updateRoutineExecutionCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            WorkspaceAuthorization authorization,
            boolean completed
    );

    @Transactional
    default RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    ) {
        return updateRoutineExecutionCompletionAuthorized(
                teamId,
                seasonId,
                roundId,
                executionId,
                legacy(accessKey),
                completed
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    record UpdateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    record SeasonRoundResult(
            UUID id,
            String name,
            LocalDate meetingDate,
            List<RoutineExecutionResult> routineExecutions,
            Instant archivedAt,
            RoundOrigin origin,
            LocalDate scheduledOccurrenceDate,
            Instant scheduledAt,
            RoundTimingStatus timingStatus
    ) {
        public SeasonRoundResult(
                UUID id,
                String name,
                LocalDate meetingDate,
                List<RoutineExecutionResult> routineExecutions,
                Instant archivedAt
        ) {
            this(
                    id,
                    name,
                    meetingDate,
                    routineExecutions,
                    archivedAt,
                    RoundOrigin.MANUAL,
                    null,
                    null,
                    RoundTimingStatus.PLANNED
            );
        }
    }

    record RoutineExecutionResult(
            UUID id,
            UUID roundId,
            UUID routineId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            RoutineStatus status,
            String detail,
            Instant deadlineAt,
            RoutineTimingStatus timingStatus
    ) {
        public RoutineExecutionResult(
                UUID id,
                UUID roundId,
                UUID routineId,
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                RoutineStatus status,
                String detail
        ) {
            this(
                    id,
                    roundId,
                    routineId,
                    title,
                    phase,
                    dueLabel,
                    ownerRoleId,
                    status,
                    detail,
                    null,
                    RoutineTimingStatus.UNSCHEDULED
            );
        }
    }
}
