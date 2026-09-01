package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceLifecycleUseCase {

    WorkspaceUseCase.CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            WorkspaceUseCase.CreateWorkspaceCommand command
    );

    WorkspaceUseCase.AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    );

    WorkspaceUseCase.AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    );

    WorkspaceUseCase.WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey);

    WorkspaceUseCase.SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            WorkspaceUseCase.UpdateSeasonCommand command
    );

    WorkspaceUseCase.SeasonResult correctSeasonName(
            UUID teamId,
            UUID seasonId,
            String recoveryKey,
            String name
    );

    WorkspaceUseCase.SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    );

    WorkspaceUseCase.SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            WorkspaceUseCase.UpdateRoundScheduleCommand command
    );

    WorkspaceUseCase.NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateNextSeasonCommand command
    );
}
