package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsUseCase;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceOperationsService implements WorkspaceOperationsUseCase {

    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceRoutineCoordinator routineCoordinator;
    private final WorkspaceRoundCoordinator roundCoordinator;

    public WorkspaceOperationsService(
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceRoutineCoordinator routineCoordinator,
            WorkspaceRoundCoordinator roundCoordinator
    ) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.routineCoordinator = routineCoordinator;
        this.roundCoordinator = roundCoordinator;
    }

    @Override
    @Transactional
    public RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoutineCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return routineCoordinator.create(teamId, scope.season(), idempotencyKey, command);
    }

    @Override
    @Transactional
    public RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            UpdateRoutineCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return routineCoordinator.update(teamId, scope.season(), routineId, command);
    }

    @Override
    @Transactional
    public RoutineResult updateRoutineArchive(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            boolean archived
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return routineCoordinator.updateArchive(scope.season(), routineId, archived);
    }

    @Override
    @Transactional
    public SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateSeasonRoundCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return roundCoordinator.create(teamId, scope.season(), idempotencyKey, command);
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            UpdateSeasonRoundCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roundCoordinator.update(scope.season(), roundId, command);
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roundCoordinator.updateArchive(scope.season(), roundId, archived);
    }

    @Override
    @Transactional
    public RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roundCoordinator.updateExecutionCompletion(
                scope.season(),
                roundId,
                executionId,
                completed
        );
    }
}
