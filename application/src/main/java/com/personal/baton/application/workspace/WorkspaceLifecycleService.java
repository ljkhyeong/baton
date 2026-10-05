package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract.AccessKeyResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.NextSeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.WorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.domain.workspace.Season;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceLifecycleService implements WorkspaceLifecycleUseCase {

    private final WorkspaceProjectionReader projectionReader;
    private final WorkspaceAccessControl accessControl;
    private final WorkspaceCreationCoordinator creationCoordinator;
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceAccessKeyCoordinator accessKeyCoordinator;
    private final WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator;
    private final WorkspaceSeasonEndingCoordinator seasonEndingCoordinator;
    private final WorkspaceSeasonSuccessorCoordinator seasonSuccessorCoordinator;

    public WorkspaceLifecycleService(
            WorkspaceProjectionReader projectionReader,
            WorkspaceAccessControl accessControl,
            WorkspaceCreationCoordinator creationCoordinator,
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceAccessKeyCoordinator accessKeyCoordinator,
            WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator,
            WorkspaceSeasonEndingCoordinator seasonEndingCoordinator,
            WorkspaceSeasonSuccessorCoordinator seasonSuccessorCoordinator
    ) {
        this.projectionReader = projectionReader;
        this.accessControl = accessControl;
        this.creationCoordinator = creationCoordinator;
        this.scopeAuthorizer = scopeAuthorizer;
        this.accessKeyCoordinator = accessKeyCoordinator;
        this.seasonSettingsCoordinator = seasonSettingsCoordinator;
        this.seasonEndingCoordinator = seasonEndingCoordinator;
        this.seasonSuccessorCoordinator = seasonSuccessorCoordinator;
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    ) {
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        accessControl.verifyWorkspaceCreationPermission(creationKey);
        return creationCoordinator.create(idempotencyKey, command);
    }

    @Override
    @Transactional
    public AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    ) {
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return accessKeyCoordinator.rotate(teamId, seasonId, idempotencyKey, currentAccessKey);
    }

    @Override
    @Transactional
    public AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    ) {
        accessControl.verifyWorkspaceRecoveryPermission(recoveryKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return accessKeyCoordinator.recover(teamId, seasonId, idempotencyKey);
    }

    @Override
    public WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey) {
        return projectionReader.read(scopeAuthorizer.authorizeRead(teamId, seasonId, accessKey));
    }

    @Override
    @Transactional
    public SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateSeasonCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeAdministratorMutation(teamId, seasonId, accessKey);
        return seasonSettingsCoordinator.updateSeason(teamId, scope.season(), command);
    }

    @Override
    @Transactional
    public SeasonResult correctSeasonName(UUID teamId, UUID seasonId, String recoveryKey, String name) {
        accessControl.verifyWorkspaceRecoveryPermission(recoveryKey);
        WorkspaceScope scope = scopeAuthorizer.requireSeasonForUpdate(teamId, seasonId);
        Season season = scope.season();
        return seasonSettingsCoordinator.updateSeason(
                teamId,
                season,
                new UpdateSeasonCommand(name, season.getStartDate(), season.getEndDate())
        );
    }

    @Override
    @Transactional
    public SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateRoundScheduleCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeAdministratorMutation(teamId, seasonId, accessKey);
        return seasonSettingsCoordinator.updateRoundSchedule(scope.season(), command);
    }

    @Override
    @Transactional
    public SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonLifecycle(teamId, seasonId, accessKey);
        return seasonEndingCoordinator.update(teamId, scope.season(), ended);
    }

    @Override
    @Transactional
    public NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            CreateNextSeasonCommand command
    ) {
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonLifecycle(
                teamId,
                sourceSeasonId,
                accessKey
        );
        return seasonSuccessorCoordinator.createNext(
                teamId,
                scope.season(),
                idempotencyKey,
                command
        );
    }
}
