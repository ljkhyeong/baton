package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceLifecycleUseCase {

    WorkspaceContract.CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            WorkspaceContract.CreateWorkspaceCommand command
    );

    WorkspaceContract.AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    );

    WorkspaceContract.AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    );

    WorkspaceContract.WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey);

    WorkspaceContract.SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            WorkspaceContract.UpdateSeasonCommand command
    );

    WorkspaceContract.SeasonResult correctSeasonName(
            UUID teamId,
            UUID seasonId,
            String recoveryKey,
            String name
    );

    WorkspaceContract.SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    );

    WorkspaceContract.SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            WorkspaceContract.UpdateRoundScheduleCommand command
    );

    WorkspaceContract.NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateNextSeasonCommand command
    );
}
