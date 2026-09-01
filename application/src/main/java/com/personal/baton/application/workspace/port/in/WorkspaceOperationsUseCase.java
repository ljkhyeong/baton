package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceOperationsUseCase {

    WorkspaceUseCase.RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateRoutineCommand command
    );

    WorkspaceUseCase.RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            WorkspaceUseCase.UpdateRoutineCommand command
    );

    WorkspaceUseCase.RoutineResult updateRoutineArchive(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            boolean archived
    );

    WorkspaceUseCase.SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateSeasonRoundCommand command
    );

    WorkspaceUseCase.SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            WorkspaceUseCase.UpdateSeasonRoundCommand command
    );

    WorkspaceUseCase.SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    );

    WorkspaceUseCase.RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    );
}
