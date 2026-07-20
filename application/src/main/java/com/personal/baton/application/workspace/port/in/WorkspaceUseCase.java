package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkspaceUseCase {

    CreatedWorkspaceResult createWorkspace(CreateWorkspaceCommand command);

    WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey);

    RoleResult createRole(UUID teamId, UUID seasonId, String accessKey, CreateRoleCommand command);

    RoutineResult createRoutine(UUID teamId, UUID seasonId, String accessKey, CreateRoutineCommand command);

    RoutineResult updateRoutineCompletion(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            boolean completed
    );

    DecisionResult createDecision(UUID teamId, UUID seasonId, String accessKey, CreateDecisionCommand command);

    HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
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

    record CreateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
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

    record WorkspaceResult(
            TeamResult team,
            SeasonResult season,
            List<MemberResult> members,
            List<RoleResult> roles,
            List<RoutineResult> routines,
            List<DecisionResult> decisions,
            List<HandoffItemResult> handoffItems
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
}
