package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import java.util.List;
import java.util.UUID;

public final class WorkspaceRecordCommands {

    private WorkspaceRecordCommands() {
    }

    public record CreateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    public record UpdateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    public record CreateHandoffItemCommand(
            UUID roleId,
            String label,
            HandoffCategory category
    ) {
    }

    public record UpdateHandoffItemCommand(
            UUID roleId,
            String label,
            HandoffCategory category
    ) {
    }

    public record CreateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }

    public record UpdateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }
}
