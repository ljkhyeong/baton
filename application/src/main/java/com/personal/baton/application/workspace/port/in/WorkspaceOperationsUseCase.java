package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceOperationsUseCase {

    WorkspaceContract.RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateRoutineCommand command
    );

    WorkspaceContract.RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            WorkspaceContract.UpdateRoutineCommand command
    );

    WorkspaceContract.RoutineResult updateRoutineArchive(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            boolean archived
    );

    WorkspaceContract.SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateSeasonRoundCommand command
    );

    WorkspaceContract.SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            WorkspaceContract.UpdateSeasonRoundCommand command
    );

    WorkspaceContract.SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    );

    WorkspaceContract.RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    );
}
