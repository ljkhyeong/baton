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
import com.personal.baton.domain.workspace.DecisionTextFormat;
import com.personal.baton.domain.workspace.TeamPermission;

public final class WorkspaceContract {

    private WorkspaceContract() {
    }

    public record CreatedWorkspaceResult(UUID teamId, UUID seasonId, String accessKey) {
    }

    public record AccessKeyResult(String accessKey) {
    }

    public record WorkspaceResult(
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

    public record TeamResult(UUID id, String name, boolean accountAccessEnabled, TeamPermission permission) {
        public TeamResult(UUID id, String name) { this(id, name, false, null); }
    }

    public record SeasonResult(
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

    public record SeasonSummaryResult(
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

    public record RoundScheduleResult(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
    }

    public record NextSeasonResult(
            SeasonResult sourceSeason,
            SeasonResult season,
            List<CopiedRoleResult> copiedRoles,
            List<CopiedRoutineResult> copiedRoutines
    ) {
    }

    public record CopiedRoleResult(UUID sourceRoleId, UUID roleId) {
    }

    public record CopiedRoutineResult(UUID sourceRoutineId, UUID routineId) {
    }

    public record MemberResult(UUID id, String name, String initials, String tone, Instant deactivatedAt) {
    }

    public record RoleResult(
            UUID id,
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk,
            UUID previousRoleId
    ) {
    }

    public record RoleHandoffTransitionResult(
            RoleResult role,
            RoleHandoffResult handoff
    ) {
    }

    public record RoleHandoffResult(
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

    public record RoutineResult(
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

    public record SeasonRoundResult(
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

    public record RoutineExecutionResult(
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

    public record DecisionResult(
            UUID id,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            UUID authorMemberId,
            String authorName,
            List<UUID> roleIds,
            Instant archivedAt,
            DecisionTextFormat textFormat
    ) {
        public DecisionResult(UUID id, String title, String reason, String alternative, Instant createdAt,
                              UUID authorMemberId, String authorName, List<UUID> roleIds, Instant archivedAt) {
            this(id, title, reason, alternative, createdAt, authorMemberId, authorName, roleIds,
                    archivedAt, DecisionTextFormat.PLAIN_TEXT);
        }
    }

    public record HandoffItemResult(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt,
            Instant archivedAt
    ) {
    }

    public record RoleResourceResult(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt,
            Instant archivedAt,
            String thumbnailUrl
    ) {
        public RoleResourceResult(UUID id, UUID roleId, String title, String url, String description,
                Instant createdAt, Instant archivedAt) {
            this(id, roleId, title, url, description, createdAt, archivedAt, null);
        }
    }

    public record ContinuitySignalResult(
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
