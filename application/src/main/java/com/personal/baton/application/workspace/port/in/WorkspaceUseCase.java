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
import org.springframework.transaction.annotation.Transactional;

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

    WorkspaceResult getWorkspaceAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    );

    RoleResourceResult getRoleResourceForGrantAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization
    );

    @Transactional(readOnly = true)
    default WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey) {
        return getWorkspaceAuthorized(teamId, seasonId, legacy(accessKey));
    }

    SeasonResult updateSeasonAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateSeasonCommand command
    );

    @Transactional
    default SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateSeasonCommand command
    ) {
        return updateSeasonAuthorized(teamId, seasonId, legacy(accessKey), command);
    }

    SeasonResult updateSeasonEndingAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            boolean ended
    );

    @Transactional
    default SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    ) {
        return updateSeasonEndingAuthorized(teamId, seasonId, legacy(accessKey), ended);
    }

    SeasonResult updateRoundScheduleAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateRoundScheduleCommand command
    );

    @Transactional
    default SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateRoundScheduleCommand command
    ) {
        return updateRoundScheduleAuthorized(teamId, seasonId, legacy(accessKey), command);
    }

    NextSeasonResult createNextSeasonAuthorized(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateNextSeasonCommand command
    );

    @Transactional
    default NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            CreateNextSeasonCommand command
    ) {
        return createNextSeasonAuthorized(
                teamId,
                sourceSeasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    MemberResult createMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateMemberCommand command
    );

    @Transactional
    default MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateMemberCommand command
    ) {
        return createMemberAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    MemberResult updateMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            UpdateMemberCommand command
    );

    @Transactional
    default MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            UpdateMemberCommand command
    ) {
        return updateMemberAuthorized(
                teamId,
                seasonId,
                memberId,
                legacy(accessKey),
                command
        );
    }

    MemberResult updateMemberDeactivationAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            boolean deactivated
    );

    @Transactional
    default MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    ) {
        return updateMemberDeactivationAuthorized(
                teamId,
                seasonId,
                memberId,
                legacy(accessKey),
                deactivated
        );
    }

    RoleResult createRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleCommand command
    );

    @Transactional
    default RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    ) {
        return createRoleAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleResult updateRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            WorkspaceAuthorization authorization,
            UpdateRoleCommand command
    );

    @Transactional
    default RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    ) {
        return updateRoleAuthorized(teamId, seasonId, roleId, legacy(accessKey), command);
    }

    RoleHandoffTransitionResult prepareRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            PrepareRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            PrepareRoleHandoffCommand command
    ) {
        return prepareRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult transferRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            TransferRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            TransferRoleHandoffCommand command
    ) {
        return transferRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult acceptRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        return acceptRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult cancelRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        return cancelRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    RoutineResult createRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoutineCommand command
    );

    @Transactional
    default RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoutineCommand command
    ) {
        return createRoutineAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoutineResult updateRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            WorkspaceAuthorization authorization,
            UpdateRoutineCommand command
    );

    @Transactional
    default RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            UpdateRoutineCommand command
    ) {
        return updateRoutineAuthorized(
                teamId,
                seasonId,
                routineId,
                legacy(accessKey),
                command
        );
    }

    SeasonRoundResult createSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateSeasonRoundCommand command
    );

    @Transactional
    default SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateSeasonRoundCommand command
    ) {
        return createSeasonRoundAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    SeasonRoundResult updateSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            UpdateSeasonRoundCommand command
    );

    @Transactional
    default SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            UpdateSeasonRoundCommand command
    ) {
        return updateSeasonRoundAuthorized(
                teamId,
                seasonId,
                roundId,
                legacy(accessKey),
                command
        );
    }

    SeasonRoundResult updateSeasonRoundArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    ) {
        return updateSeasonRoundArchiveAuthorized(
                teamId,
                seasonId,
                roundId,
                legacy(accessKey),
                archived
        );
    }

    RoutineExecutionResult updateRoutineExecutionCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            WorkspaceAuthorization authorization,
            boolean completed
    );

    @Transactional
    default RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    ) {
        return updateRoutineExecutionCompletionAuthorized(
                teamId,
                seasonId,
                roundId,
                executionId,
                legacy(accessKey),
                completed
        );
    }

    DecisionResult createDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateDecisionCommand command
    );

    @Transactional
    default DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    ) {
        return createDecisionAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    DecisionResult updateDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            UpdateDecisionCommand command
    );

    @Transactional
    default DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            UpdateDecisionCommand command
    ) {
        return updateDecisionAuthorized(
                teamId,
                seasonId,
                decisionId,
                legacy(accessKey),
                command
        );
    }

    DecisionResult updateDecisionArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    ) {
        return updateDecisionArchiveAuthorized(
                teamId,
                seasonId,
                decisionId,
                legacy(accessKey),
                archived
        );
    }

    HandoffItemResult createHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateHandoffItemCommand command
    );

    @Transactional
    default HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        return createHandoffItemAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    HandoffItemResult updateHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            UpdateHandoffItemCommand command
    );

    @Transactional
    default HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            UpdateHandoffItemCommand command
    ) {
        return updateHandoffItemAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                command
        );
    }

    HandoffItemResult updateHandoffItemCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean completed
    );

    @Transactional
    default HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    ) {
        return updateHandoffItemCompletionAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                completed
        );
    }

    HandoffItemResult updateHandoffItemArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    ) {
        return updateHandoffItemArchiveAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                archived
        );
    }

    RoleResourceResult createRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleResourceCommand command
    );

    @Transactional
    default RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    ) {
        return createRoleResourceAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleResourceResult updateRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization,
            UpdateRoleResourceCommand command
    );

    @Transactional
    default RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    ) {
        return updateRoleResourceAuthorized(
                teamId,
                seasonId,
                resourceId,
                legacy(accessKey),
                command
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

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
        public CreateRoutineCommand(
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
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
        public UpdateRoutineCommand(
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
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
            List<RoleHandoffResult> roleHandoffs
    ) {
        public WorkspaceResult(
                TeamResult team,
                SeasonResult season,
                List<SeasonSummaryResult> seasons,
                List<MemberResult> members,
                List<RoleResult> roles,
                List<RoutineResult> routines,
                List<SeasonRoundResult> rounds,
                List<DecisionResult> decisions,
                List<HandoffItemResult> handoffItems,
                List<RoleResourceResult> resources
        ) {
            this(
                    team,
                    season,
                    seasons,
                    members,
                    roles,
                    routines,
                    rounds,
                    decisions,
                    handoffItems,
                    resources,
                    List.of()
            );
        }
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
        public SeasonResult(
                UUID id,
                String name,
                LocalDate startDate,
                LocalDate endDate,
                Instant endedAt,
                UUID previousSeasonId
        ) {
            this(
                    id,
                    name,
                    startDate,
                    endDate,
                    endedAt,
                    previousSeasonId,
                    "Asia/Seoul",
                    null
            );
        }
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
        public SeasonSummaryResult(
                UUID id,
                String name,
                LocalDate startDate,
                LocalDate endDate,
                Instant endedAt,
                UUID previousSeasonId
        ) {
            this(
                    id,
                    name,
                    startDate,
                    endDate,
                    endedAt,
                    previousSeasonId,
                    "Asia/Seoul",
                    null
            );
        }
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
            LocalTime deadlineTime
    ) {
        public RoutineResult(
                UUID id,
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(id, title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
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
        public SeasonRoundResult(
                UUID id,
                String name,
                LocalDate meetingDate,
                List<RoutineExecutionResult> routineExecutions,
                Instant archivedAt
        ) {
            this(
                    id,
                    name,
                    meetingDate,
                    routineExecutions,
                    archivedAt,
                    RoundOrigin.MANUAL,
                    null,
                    null,
                    RoundTimingStatus.PLANNED
            );
        }
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
        public RoutineExecutionResult(
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
            this(
                    id,
                    roundId,
                    routineId,
                    title,
                    phase,
                    dueLabel,
                    ownerRoleId,
                    status,
                    detail,
                    null,
                    RoutineTimingStatus.UNSCHEDULED
            );
        }
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
            Instant archivedAt
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
