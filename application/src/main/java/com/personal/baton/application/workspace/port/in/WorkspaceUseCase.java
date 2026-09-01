package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface WorkspaceUseCase extends
        WorkspaceLifecycleUseCase,
        WorkspacePeopleUseCase,
        WorkspaceOperationsUseCase,
        WorkspaceRecordsUseCase {

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

    record CreateMemberCommand(String name) {
    }

    record UpdateMemberCommand(String name) {
    }

    record UpdateSeasonCommand(String name, LocalDate startDate, LocalDate endDate) {
    }

    record UpdateRoundScheduleCommand(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled
    ) {
    }

    record CreateNextSeasonCommand(
            String name,
            LocalDate startDate,
            LocalDate endDate,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
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

    record PrepareRoleHandoffCommand(
            UUID toMemberId,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate
    ) {
    }

    record TransferRoleHandoffCommand(
            UUID confirmedByMemberId,
            boolean warningAcknowledged
    ) {
    }

    record ConfirmRoleHandoffCommand(UUID confirmedByMemberId) {
    }

    record CreateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
    }

    record UpdateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
    }

    record CreateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    record UpdateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    record CreateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    record UpdateDecisionCommand(
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

    record UpdateHandoffItemCommand(
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
            List<SeasonSummaryResult> seasons,
            List<MemberResult> members,
            List<RoleResult> roles,
            List<RoutineResult> routines,
            List<SeasonRoundResult> rounds,
            List<DecisionResult> decisions,
            List<HandoffItemResult> handoffItems,
            List<RoleResourceResult> resources,
            List<RoleHandoffResult> roleHandoffs,
            List<ContinuitySignalResult> continuitySignals
    ) {
    }

    record TeamResult(UUID id, String name) {
    }

    record SeasonResult(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Instant endedAt,
            UUID previousSeasonId,
            String timeZone,
            RoundScheduleResult roundSchedule
    ) {
    }

    record SeasonSummaryResult(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Instant endedAt,
            UUID previousSeasonId,
            String timeZone,
            RoundScheduleResult roundSchedule
    ) {
    }

    record RoundScheduleResult(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
    }

    record NextSeasonResult(
            SeasonResult sourceSeason,
            SeasonResult season,
            List<CopiedRoleResult> copiedRoles,
            List<CopiedRoutineResult> copiedRoutines
    ) {
    }

    record CopiedRoleResult(UUID sourceRoleId, UUID roleId) {
    }

    record CopiedRoutineResult(UUID sourceRoutineId, UUID routineId) {
    }

    record MemberResult(UUID id, String name, String initials, String tone, Instant deactivatedAt) {
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

    record RoleHandoffTransitionResult(
            RoleResult role,
            RoleHandoffResult handoff
    ) {
    }

    record RoleHandoffResult(
            UUID id,
            UUID roleId,
            UUID fromMemberId,
            UUID toMemberId,
            LocalDate outgoingAssignmentStartDate,
            LocalDate outgoingAssignmentEndDate,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate,
            RoleHandoffStatus status,
            Instant preparedAt,
            Instant transferredAt,
            Instant acceptedAt,
            Instant cancelledAt,
            UUID transferredByMemberId,
            UUID acceptedByMemberId,
            UUID cancelledByMemberId,
            Integer activeItemCount,
            Integer incompleteItemCount,
            Integer resourceCount,
            boolean warningAcknowledged
    ) {
    }

    record RoutineResult(
            UUID id,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime,
            Instant archivedAt
    ) {
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
    }

    record DecisionResult(
            UUID id,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            UUID authorMemberId,
            String authorName,
            List<UUID> roleIds,
            Instant archivedAt
    ) {
    }

    record HandoffItemResult(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt,
            Instant archivedAt
    ) {
    }

    record RoleResourceResult(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt,
            Instant archivedAt
    ) {
    }

    record ContinuitySignalResult(
            ContinuitySignalType type,
            ContinuitySignalSeverity severity,
            UUID roleId,
            UUID routineId,
            String title,
            String reason,
            String recommendedAction,
            LocalDate relevantDate
    ) {
    }
}
