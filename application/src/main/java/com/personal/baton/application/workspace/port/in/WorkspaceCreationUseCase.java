package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkspaceCreationUseCase {

    CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    );

    CreatedWorkspaceResult createWorkspaceForOwner(
            String idempotencyKey,
            AuthenticatedAccount authenticatedAccount,
            String ownerMemberName,
            CreateWorkspaceCommand command
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
}
