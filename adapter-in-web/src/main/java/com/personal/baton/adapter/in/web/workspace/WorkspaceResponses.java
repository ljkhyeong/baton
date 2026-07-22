package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
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
            List<MemberResponse> members,
            List<RoleResponse> roles,
            List<RoutineResponse> routines,
            List<SeasonRoundResponse> rounds,
            List<DecisionResponse> decisions,
            List<HandoffItemResponse> handoffItems,
            List<RoleResourceResponse> resources
    ) {

        public static WorkspaceResponse from(WorkspaceUseCase.WorkspaceResult result) {
            return new WorkspaceResponse(
                    TeamResponse.from(result.team()),
                    SeasonResponse.from(result.season()),
                    result.members().stream().map(MemberResponse::from).toList(),
                    result.roles().stream().map(RoleResponse::from).toList(),
                    result.routines().stream().map(RoutineResponse::from).toList(),
                    result.rounds().stream().map(SeasonRoundResponse::from).toList(),
                    result.decisions().stream().map(DecisionResponse::from).toList(),
                    result.handoffItems().stream().map(HandoffItemResponse::from).toList(),
                    result.resources().stream().map(RoleResourceResponse::from).toList()
            );
        }
    }

    public record TeamResponse(UUID id, String name) {

        static TeamResponse from(WorkspaceUseCase.TeamResult result) {
            return new TeamResponse(result.id(), result.name());
        }
    }

    public record SeasonResponse(UUID id, String name, LocalDate startDate, LocalDate endDate) {

        static SeasonResponse from(WorkspaceUseCase.SeasonResult result) {
            return new SeasonResponse(result.id(), result.name(), result.startDate(), result.endDate());
        }
    }

    public record MemberResponse(UUID id, String name, String initials, String tone) {

        static MemberResponse from(WorkspaceUseCase.MemberResult result) {
            return new MemberResponse(result.id(), result.name(), result.initials(), result.tone());
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

    public record RoutineResponse(
            UUID id,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {

        public static RoutineResponse from(WorkspaceUseCase.RoutineResult result) {
            return new RoutineResponse(
                    result.id(),
                    result.title(),
                    result.phase(),
                    result.dueLabel(),
                    result.ownerRoleId(),
                    result.detail()
            );
        }
    }

    public record SeasonRoundResponse(
            UUID id,
            String name,
            LocalDate meetingDate,
            List<RoutineExecutionResponse> routineExecutions
    ) {

        public static SeasonRoundResponse from(WorkspaceUseCase.SeasonRoundResult result) {
            return new SeasonRoundResponse(
                    result.id(),
                    result.name(),
                    result.meetingDate(),
                    result.routineExecutions().stream().map(RoutineExecutionResponse::from).toList()
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
            String detail
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
                    result.detail()
            );
        }
    }

    public record DecisionResponse(
            UUID id,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            String authorName,
            List<UUID> roleIds
    ) {

        public static DecisionResponse from(WorkspaceUseCase.DecisionResult result) {
            return new DecisionResponse(
                    result.id(),
                    result.title(),
                    result.reason(),
                    result.alternative(),
                    result.createdAt(),
                    result.authorName(),
                    result.roleIds()
            );
        }
    }

    public record HandoffItemResponse(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed
    ) {

        public static HandoffItemResponse from(WorkspaceUseCase.HandoffItemResult result) {
            return new HandoffItemResponse(
                    result.id(),
                    result.roleId(),
                    result.label(),
                    result.category(),
                    result.completed()
            );
        }
    }

    public record RoleResourceResponse(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description
    ) {

        public static RoleResourceResponse from(WorkspaceUseCase.RoleResourceResult result) {
            return new RoleResourceResponse(
                    result.id(),
                    result.roleId(),
                    result.title(),
                    result.url(),
                    result.description()
            );
        }
    }
}
