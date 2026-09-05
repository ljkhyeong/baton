package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.DecisionTextFormat;
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
            List<UUID> roleIds,
            DecisionTextFormat textFormat
    ) {
        public CreateDecisionCommand(String title, String reason, String alternative,
                                     UUID authorMemberId, List<UUID> roleIds) {
            this(title, reason, alternative, authorMemberId, roleIds, null);
        }
    }

    public record UpdateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds,
            DecisionTextFormat textFormat
    ) {
        public UpdateDecisionCommand(String title, String reason, String alternative,
                                     UUID authorMemberId, List<UUID> roleIds) {
            this(title, reason, alternative, authorMemberId, roleIds, null);
        }
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
