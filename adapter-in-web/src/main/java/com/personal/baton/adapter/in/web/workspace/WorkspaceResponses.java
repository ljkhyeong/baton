package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
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

public final class WorkspaceResponses {

    private WorkspaceResponses() {
    }

    public record CreateWorkspaceResponse(UUID teamId, UUID seasonId, String accessKey) {

        public static CreateWorkspaceResponse from(WorkspaceUseCase.CreatedWorkspaceResult result) {
            return new CreateWorkspaceResponse(result.teamId(), result.seasonId(), result.accessKey());
        }
    }

    public record AccessKeyResponse(String accessKey) {

        public static AccessKeyResponse from(WorkspaceUseCase.AccessKeyResult result) {
            return new AccessKeyResponse(result.accessKey());
        }
    }

    public record WorkspaceResponse(
            TeamResponse team,
            SeasonResponse season,
            List<SeasonResponse> seasons,
            List<MemberResponse> members,
            List<RoleResponse> roles,
            List<RoutineResponse> routines,
            List<SeasonRoundResponse> rounds,
            List<DecisionResponse> decisions,
            List<HandoffItemResponse> handoffItems,
            List<RoleResourceResponse> resources,
            List<RoleHandoffResponse> roleHandoffs,
            List<ContinuitySignalResponse> continuitySignals
    ) {

        public static WorkspaceResponse from(WorkspaceUseCase.WorkspaceResult result) {
            return new WorkspaceResponse(
                    TeamResponse.from(result.team()),
                    SeasonResponse.from(result.season()),
                    result.seasons().stream().map(SeasonResponse::from).toList(),
                    result.members().stream().map(MemberResponse::from).toList(),
                    result.roles().stream().map(RoleResponse::from).toList(),
                    result.routines().stream().map(RoutineResponse::from).toList(),
                    result.rounds().stream().map(SeasonRoundResponse::from).toList(),
                    result.decisions().stream().map(DecisionResponse::from).toList(),
                    result.handoffItems().stream().map(HandoffItemResponse::from).toList(),
                    result.resources().stream().map(RoleResourceResponse::from).toList(),
                    result.roleHandoffs().stream().map(RoleHandoffResponse::from).toList(),
                    result.continuitySignals().stream()
                            .map(ContinuitySignalResponse::from)
                            .toList()
            );
        }
    }

    public record NextSeasonResponse(
            SeasonResponse sourceSeason,
            SeasonResponse season,
            List<CopiedRoleResponse> copiedRoles,
            List<CopiedRoutineResponse> copiedRoutines
    ) {

        public static NextSeasonResponse from(WorkspaceUseCase.NextSeasonResult result) {
            return new NextSeasonResponse(
                    SeasonResponse.from(result.sourceSeason()),
                    SeasonResponse.from(result.season()),
                    result.copiedRoles().stream().map(CopiedRoleResponse::from).toList(),
                    result.copiedRoutines().stream().map(CopiedRoutineResponse::from).toList()
            );
        }
    }

    public record TeamResponse(UUID id, String name) {

        static TeamResponse from(WorkspaceUseCase.TeamResult result) {
            return new TeamResponse(result.id(), result.name());
        }
    }

    public record SeasonResponse(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Instant endedAt,
            UUID previousSeasonId,
            String timeZone,
            RoundScheduleResponse roundSchedule
    ) {

        static SeasonResponse from(WorkspaceUseCase.SeasonResult result) {
            return new SeasonResponse(
                    result.id(),
                    result.name(),
                    result.startDate(),
                    result.endDate(),
                    result.endedAt(),
                    result.previousSeasonId(),
                    result.timeZone(),
                    RoundScheduleResponse.from(result.roundSchedule())
            );
        }

        static SeasonResponse from(WorkspaceUseCase.SeasonSummaryResult result) {
            return new SeasonResponse(
                    result.id(),
                    result.name(),
                    result.startDate(),
                    result.endDate(),
                    result.endedAt(),
                    result.previousSeasonId(),
                    result.timeZone(),
                    RoundScheduleResponse.from(result.roundSchedule())
            );
        }
    }

    public record RoundScheduleResponse(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {

        static RoundScheduleResponse from(WorkspaceUseCase.RoundScheduleResult result) {
            if (result == null) {
                return null;
            }
            return new RoundScheduleResponse(
                    result.firstMeetingDate(),
                    result.meetingTime(),
                    result.recurrence(),
                    result.generationLeadDays(),
                    result.enabled(),
                    result.nextOccurrenceDate()
            );
        }
    }

    public record CopiedRoleResponse(UUID sourceRoleId, UUID roleId) {

        static CopiedRoleResponse from(WorkspaceUseCase.CopiedRoleResult result) {
            return new CopiedRoleResponse(result.sourceRoleId(), result.roleId());
        }
    }

    public record CopiedRoutineResponse(UUID sourceRoutineId, UUID routineId) {

        static CopiedRoutineResponse from(WorkspaceUseCase.CopiedRoutineResult result) {
            return new CopiedRoutineResponse(result.sourceRoutineId(), result.routineId());
        }
    }

    public record MemberResponse(
            UUID id,
            String name,
            String initials,
            String tone,
            Instant deactivatedAt
    ) {

        static MemberResponse from(WorkspaceUseCase.MemberResult result) {
            return new MemberResponse(
                    result.id(),
                    result.name(),
                    result.initials(),
                    result.tone(),
                    result.deactivatedAt()
            );
        }
    }

    public record RoleResponse(
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

        public static RoleResponse from(WorkspaceUseCase.RoleResult result) {
            return new RoleResponse(
                    result.id(),
                    result.name(),
                    result.purpose(),
                    result.currentMemberId(),
                    result.nextMemberId(),
                    result.assignmentStartDate(),
                    result.assignmentEndDate(),
                    result.responsibilities(),
                    result.risk()
            );
        }
    }

    public record RoleHandoffTransitionResponse(
            RoleResponse role,
            RoleHandoffResponse handoff
    ) {

        public static RoleHandoffTransitionResponse from(
                WorkspaceUseCase.RoleHandoffTransitionResult result
        ) {
            return new RoleHandoffTransitionResponse(
                    RoleResponse.from(result.role()),
                    RoleHandoffResponse.from(result.handoff())
            );
        }
    }

    public record RoleHandoffResponse(
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

        public static RoleHandoffResponse from(WorkspaceUseCase.RoleHandoffResult result) {
            return new RoleHandoffResponse(
                    result.id(),
                    result.roleId(),
                    result.fromMemberId(),
                    result.toMemberId(),
                    result.outgoingAssignmentStartDate(),
                    result.outgoingAssignmentEndDate(),
                    result.incomingAssignmentStartDate(),
                    result.incomingAssignmentEndDate(),
                    result.status(),
                    result.preparedAt(),
                    result.transferredAt(),
                    result.acceptedAt(),
                    result.cancelledAt(),
                    result.transferredByMemberId(),
                    result.acceptedByMemberId(),
                    result.cancelledByMemberId(),
                    result.activeItemCount(),
                    result.incompleteItemCount(),
                    result.resourceCount(),
                    result.warningAcknowledged()
            );
        }
    }

    public record RoutineResponse(
            UUID id,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {

        public static RoutineResponse from(WorkspaceUseCase.RoutineResult result) {
            return new RoutineResponse(
                    result.id(),
                    result.title(),
                    result.phase(),
                    result.dueLabel(),
                    result.ownerRoleId(),
                    result.detail(),
                    result.deadlineDayOffset(),
                    result.deadlineTime()
            );
        }
    }

    public record SeasonRoundResponse(
            UUID id,
            String name,
            LocalDate meetingDate,
            List<RoutineExecutionResponse> routineExecutions,
            Instant archivedAt,
            RoundOrigin origin,
            LocalDate scheduledOccurrenceDate,
            Instant scheduledAt,
            RoundTimingStatus timingStatus
    ) {

        public static SeasonRoundResponse from(WorkspaceUseCase.SeasonRoundResult result) {
            return new SeasonRoundResponse(
                    result.id(),
                    result.name(),
                    result.meetingDate(),
                    result.routineExecutions().stream().map(RoutineExecutionResponse::from).toList(),
                    result.archivedAt(),
                    result.origin(),
                    result.scheduledOccurrenceDate(),
                    result.scheduledAt(),
                    result.timingStatus()
            );
        }
    }

    public record RoutineExecutionResponse(
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

        public static RoutineExecutionResponse from(WorkspaceUseCase.RoutineExecutionResult result) {
            return new RoutineExecutionResponse(
                    result.id(),
                    result.roundId(),
                    result.routineId(),
                    result.title(),
                    result.phase(),
                    result.dueLabel(),
                    result.ownerRoleId(),
                    result.status(),
                    result.detail(),
                    result.deadlineAt(),
                    result.timingStatus()
            );
        }
    }

    public record DecisionResponse(
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

        public static DecisionResponse from(WorkspaceUseCase.DecisionResult result) {
            return new DecisionResponse(
                    result.id(),
                    result.title(),
                    result.reason(),
                    result.alternative(),
                    result.createdAt(),
                    result.authorMemberId(),
                    result.authorName(),
                    result.roleIds(),
                    result.archivedAt()
            );
        }
    }

    public record HandoffItemResponse(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt,
            Instant archivedAt
    ) {

        public static HandoffItemResponse from(WorkspaceUseCase.HandoffItemResult result) {
            return new HandoffItemResponse(
                    result.id(),
                    result.roleId(),
                    result.label(),
                    result.category(),
                    result.completed(),
                    result.createdAt(),
                    result.archivedAt()
            );
        }
    }

    public record RoleResourceResponse(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt
    ) {

        public static RoleResourceResponse from(WorkspaceUseCase.RoleResourceResult result) {
            return new RoleResourceResponse(
                    result.id(),
                    result.roleId(),
                    result.title(),
                    result.url(),
                    result.description(),
                    result.createdAt()
            );
        }
    }

    public record ContinuitySignalResponse(
            ContinuitySignalType type,
            ContinuitySignalSeverity severity,
            UUID roleId,
            UUID routineId,
            String title,
            String reason,
            String recommendedAction,
            LocalDate relevantDate
    ) {

        public static ContinuitySignalResponse from(
                WorkspaceUseCase.ContinuitySignalResult result
        ) {
            return new ContinuitySignalResponse(
                    result.type(),
                    result.severity(),
                    result.roleId(),
                    result.routineId(),
                    result.title(),
                    result.reason(),
                    result.recommendedAction(),
                    result.relevantDate()
            );
        }
    }
}
