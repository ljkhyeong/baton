package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

    public record CreateRoutineRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull RoutinePhase phase,
            @NotBlank @Size(max = 100) String dueLabel,
            @NotNull UUID ownerRoleId,
            @NotBlank @Size(max = 1000) String detail
    ) {
    }

    public record UpdateRoutineRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull RoutinePhase phase,
            @NotBlank @Size(max = 100) String dueLabel,
            @NotNull UUID ownerRoleId,
            @NotBlank @Size(max = 1000) String detail
    ) {
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

    public record CreateHandoffItemRequest(
            @NotNull UUID roleId,
            @NotBlank @Size(max = 500) String label,
            @NotNull HandoffCategory category
    ) {
    }
}
