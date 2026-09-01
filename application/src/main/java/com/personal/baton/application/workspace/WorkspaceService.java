package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Season;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService implements WorkspaceUseCase, VerifyWorkspaceAccessUseCase {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{32,200}");
    private final WorkspaceProjectionReader projectionReader;
    private final WorkspaceAccessControl accessControl;
    private final WorkspaceCreationCoordinator creationCoordinator;
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceAccessKeyCoordinator accessKeyCoordinator;
    private final WorkspaceMemberCoordinator memberCoordinator;
    private final WorkspaceRoleCoordinator roleCoordinator;
    private final WorkspaceRoleHandoffCoordinator roleHandoffCoordinator;
    private final WorkspaceRoutineCoordinator routineCoordinator;
    private final WorkspaceRoundCoordinator roundCoordinator;
    private final WorkspaceDecisionCoordinator decisionCoordinator;
    private final WorkspaceHandoffItemCoordinator handoffItemCoordinator;
    private final WorkspaceRoleResourceCoordinator roleResourceCoordinator;
    private final WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator;
    private final WorkspaceSeasonLifecycleCoordinator seasonLifecycleCoordinator;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    public WorkspaceService(
            WorkspaceProjectionReader projectionReader,
            WorkspaceAccessControl accessControl,
            WorkspaceCreationCoordinator creationCoordinator,
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceAccessKeyCoordinator accessKeyCoordinator,
            WorkspaceMemberCoordinator memberCoordinator,
            WorkspaceRoleCoordinator roleCoordinator,
            WorkspaceRoleHandoffCoordinator roleHandoffCoordinator,
            WorkspaceRoutineCoordinator routineCoordinator,
            WorkspaceRoundCoordinator roundCoordinator,
            WorkspaceDecisionCoordinator decisionCoordinator,
            WorkspaceHandoffItemCoordinator handoffItemCoordinator,
            WorkspaceRoleResourceCoordinator roleResourceCoordinator,
            WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator,
            WorkspaceSeasonLifecycleCoordinator seasonLifecycleCoordinator,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.projectionReader = projectionReader;
        this.accessControl = accessControl;
        this.creationCoordinator = creationCoordinator;
        this.scopeAuthorizer = scopeAuthorizer;
        this.accessKeyCoordinator = accessKeyCoordinator;
        this.memberCoordinator = memberCoordinator;
        this.roleCoordinator = roleCoordinator;
        this.roleHandoffCoordinator = roleHandoffCoordinator;
        this.routineCoordinator = routineCoordinator;
        this.roundCoordinator = roundCoordinator;
        this.decisionCoordinator = decisionCoordinator;
        this.handoffItemCoordinator = handoffItemCoordinator;
        this.roleResourceCoordinator = roleResourceCoordinator;
        this.seasonSettingsCoordinator = seasonSettingsCoordinator;
        this.seasonLifecycleCoordinator = seasonLifecycleCoordinator;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    @Override
    public void verifyTeamRead(UUID teamId, String accessKey) {
        scopeAuthorizer.authorizeTeamRead(teamId, accessKey);
    }

    @Override
    public Season verifyRead(UUID teamId, UUID seasonId, String accessKey) {
        return scopeAuthorizer.authorizeRead(teamId, seasonId, accessKey).season();
    }

    @Override
    public Season verifyMutation(UUID teamId, UUID seasonId, String accessKey) {
        return scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey).season();
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    ) {
        requireValidIdempotencyKey(idempotencyKey);
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
        requireValidIdempotencyKey(idempotencyKey);
        return accessKeyCoordinator.rotate(
                teamId,
                seasonId,
                idempotencyKey,
                currentAccessKey
        );
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
        requireValidIdempotencyKey(idempotencyKey);
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
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(
                teamId,
                seasonId,
                accessKey
        );
        Season season = scope.season();
        boolean periodChanged = !season.getStartDate().equals(command.startDate())
                || !season.getEndDate().equals(command.endDate());
        SeasonResult result = seasonSettingsCoordinator.updateSeason(teamId, season, command);
        return periodChanged ? reconcileContinuitySignals(teamId, seasonId, result) : result;
    }

    @Override
    @Transactional
    public SeasonResult correctSeasonName(UUID teamId, UUID seasonId, String recoveryKey, String name) {
        accessControl.verifyWorkspaceRecoveryPermission(recoveryKey);
        WorkspaceScope scope = scopeAuthorizer.requireSeasonForUpdate(teamId, seasonId);
        Season season = scope.season();
        return seasonSettingsCoordinator.updateSeason(
                teamId, season, new UpdateSeasonCommand(name, season.getStartDate(), season.getEndDate())
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
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(
                teamId,
                seasonId,
                accessKey
        );
        String previousTimeZone = scope.season().getTimeZone();
        SeasonResult result = seasonSettingsCoordinator.updateRoundSchedule(scope.season(), command);
        return previousTimeZone.equals(result.timeZone())
                ? result
                : reconcileContinuitySignals(teamId, seasonId, result);
    }

    @Override
    @Transactional
    public SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonLifecycle(
                teamId,
                seasonId,
                accessKey
        );
        boolean changed = scope.season().isEnded() != ended;
        SeasonResult result = seasonLifecycleCoordinator.updateEnding(teamId, scope.season(), ended);
        return changed ? reconcileContinuitySignals(teamId, seasonId, result) : result;
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
        requireValidIdempotencyKey(idempotencyKey);
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonLifecycle(
                teamId,
                sourceSeasonId,
                accessKey
        );
        return seasonLifecycleCoordinator.createNext(
                teamId,
                scope.season(),
                idempotencyKey,
                command
        );
    }

    @Override
    @Transactional
    public MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateMemberCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return memberCoordinator.create(
                teamId,
                seasonId,
                idempotencyKey,
                command
        );
    }

    @Override
    @Transactional
    public MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            UpdateMemberCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return memberCoordinator.update(teamId, memberId, command);
    }

    @Override
    @Transactional
    public MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return memberCoordinator.updateDeactivation(teamId, seasonId, memberId, deactivated);
    }

    @Override
    @Transactional
    public RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return roleCoordinator.create(teamId, scope.season(), idempotencyKey, command);
    }

    @Override
    @Transactional
    public RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return reconcileContinuitySignals(
                teamId,
                seasonId,
                roleCoordinator.update(teamId, scope.season(), roleId, command)
        );
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            PrepareRoleHandoffCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return roleHandoffCoordinator.prepare(
                teamId,
                scope.season(),
                roleId,
                idempotencyKey,
                command
        );
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            TransferRoleHandoffCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.transfer(teamId, seasonId, roleId, handoffId, command);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.accept(teamId, scope.season(), roleId, handoffId, command);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.cancel(teamId, seasonId, roleId, handoffId, command);
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
        requireValidIdempotencyKey(idempotencyKey);
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
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(
                teamId,
                seasonId,
                accessKey
        );
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
        requireValidIdempotencyKey(idempotencyKey);
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
        return roundCoordinator.updateExecutionCompletion(scope.season(), roundId, executionId, completed);
    }

    @Override
    @Transactional
    public DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return decisionCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            UpdateDecisionCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return decisionCoordinator.update(teamId, seasonId, decisionId, command);
    }

    @Override
    @Transactional
    public DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return decisionCoordinator.updateArchive(teamId, seasonId, decisionId, archived);
    }

    @Override
    @Transactional
    public HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return handoffItemCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            UpdateHandoffItemCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.update(teamId, seasonId, itemId, command);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.updateCompletion(teamId, seasonId, itemId, completed);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.updateArchive(teamId, seasonId, itemId, archived);
    }

    @Override
    @Transactional
    public RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        return roleResourceCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleResourceCoordinator.update(teamId, seasonId, resourceId, command);
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResourceArchive(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleResourceCoordinator.updateArchive(teamId, seasonId, resourceId, archived);
    }

    private <T> T reconcileContinuitySignals(UUID teamId, UUID seasonId, T result) {
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return result;
    }

    private void requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new DomainValidationException(
                    "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
            );
        }
    }

}
