package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.RoutinePhase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkspaceRequests {

    private WorkspaceRequests() {
    }

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 100) String teamName,
            @NotBlank @Size(max = 100) String seasonName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 100) String> memberNames
    ) {
    }

    public record CreateMemberRequest(
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record UpdateMemberRequest(
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record MemberDeactivationRequest(@NotNull Boolean deactivated) {
    }

    public record UpdateSeasonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate
    ) {
    }

    public record CorrectSeasonNameRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record UpdateRoundScheduleRequest(
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate firstMeetingDate,
            @NotNull LocalTime meetingTime,
            @NotNull RoundRecurrence recurrence,
            @NotNull @Min(0) @Max(30) Integer generationLeadDays,
            @NotNull Boolean enabled
    ) {
    }

    public record UpdateSeasonEndingRequest(@NotNull Boolean ended) {
    }

    public record CreateNextSeasonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @Size(max = 100) List<@NotNull UUID> copyRoleIds,
            @NotNull @Size(max = 100) List<@NotNull UUID> copyRoutineIds
    ) {
    }

    public record CreateRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 500) String> responsibilities,
            @Size(max = 1000) String risk
    ) {
    }

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 500) String> responsibilities,
            @Size(max = 1000) String risk
    ) {
    }

    public record PrepareRoleHandoffRequest(
            @NotNull UUID toMemberId,
            @NotNull LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate
    ) {
    }

    public record TransferRoleHandoffRequest(
            @NotNull UUID confirmedByMemberId,
            @NotNull Boolean warningAcknowledged
    ) {
    }

    public record ConfirmRoleHandoffRequest(@NotNull UUID confirmedByMemberId) {
    }

    public record CreateRoutineRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull RoutinePhase phase,
            @NotBlank @Size(max = 100) String dueLabel,
            @Min(-30) @Max(30) Integer deadlineDayOffset,
            LocalTime deadlineTime,
            @NotNull UUID ownerRoleId,
            @NotBlank @Size(max = 1000) String detail
    ) {
    }

    public record UpdateRoutineRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull RoutinePhase phase,
            @NotBlank @Size(max = 100) String dueLabel,
            @Min(-30) @Max(30) Integer deadlineDayOffset,
            LocalTime deadlineTime,
            @NotNull UUID ownerRoleId,
            @NotBlank @Size(max = 1000) String detail
    ) {
    }

    public record CreateSeasonRoundRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate meetingDate
    ) {
    }

    public record UpdateSeasonRoundRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate meetingDate
    ) {
    }

    public record UpdateRoutineExecutionCompletionRequest(@NotNull Boolean completed) {
    }

    public record CompletionRequest(@NotNull Boolean completed) {
    }

    public record CreateDecisionRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String reason,
            @Size(max = 2000) String alternative,
            @NotNull UUID authorMemberId,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> roleIds
    ) {
    }

    public record UpdateDecisionRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String reason,
            @Size(max = 2000) String alternative,
            @NotNull UUID authorMemberId,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> roleIds
    ) {
    }

    public record CreateHandoffItemRequest(
            @NotNull UUID roleId,
            @NotBlank @Size(max = 500) String label,
            @NotNull HandoffCategory category
    ) {
    }

    public record UpdateHandoffItemRequest(
            @NotNull UUID roleId,
            @NotBlank @Size(max = 500) String label,
            @NotNull HandoffCategory category
    ) {
    }

    public record ArchiveRequest(@NotNull Boolean archived) {
    }

    public record CreateRoleResourceRequest(
            @NotNull UUID roleId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 1000) String description
    ) {
    }

    public record UpdateRoleResourceRequest(
            @NotNull UUID roleId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 1000) String description
    ) {
    }
}
