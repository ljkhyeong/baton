package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkspaceUseCase {

    CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    );

    AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    );

    AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    );

    WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey);

    RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    );

    RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    );

    RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoutineCommand command
    );

    RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            UpdateRoutineCommand command
    );

    SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateSeasonRoundCommand command
    );

    RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    );

    DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    );

    HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    );

    HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    );

    RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    );

    RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    );

    record CreateWorkspaceCommand(
            String teamName,
            String seasonName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> memberNames
    ) {
    }

    record CreatedWorkspaceResult(UUID teamId, UUID seasonId, String accessKey) {
    }

    record AccessKeyResult(String accessKey) {
    }

    record CreateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    record UpdateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    record CreateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
    }

    record UpdateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
    }

    record CreateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    record CreateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    record CreateHandoffItemCommand(
            UUID roleId,
            String label,
            HandoffCategory category
    ) {
    }

    record CreateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }

    record UpdateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }

    record WorkspaceResult(
            TeamResult team,
            SeasonResult season,
            List<MemberResult> members,
            List<RoleResult> roles,
            List<RoutineResult> routines,
            List<SeasonRoundResult> rounds,
            List<DecisionResult> decisions,
            List<HandoffItemResult> handoffItems,
            List<RoleResourceResult> resources
    ) {
    }

    record TeamResult(UUID id, String name) {
    }

    record SeasonResult(UUID id, String name, LocalDate startDate, LocalDate endDate) {
    }

    record MemberResult(UUID id, String name, String initials, String tone) {
    }

    record RoleResult(
            UUID id,
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    record RoutineResult(
            UUID id,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
    }

    record SeasonRoundResult(
            UUID id,
            String name,
            LocalDate meetingDate,
            List<RoutineExecutionResult> routineExecutions
    ) {
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
            String detail
    ) {
    }

    record DecisionResult(
            UUID id,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            String authorName,
            List<UUID> roleIds
    ) {
    }

    record HandoffItemResult(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed
    ) {
    }

    record RoleResourceResult(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }
}
