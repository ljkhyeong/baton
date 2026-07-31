package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Role;
import java.util.UUID;

final class WorkspaceRoleResolver {

    private final WorkspaceRepository repository;

    WorkspaceRoleResolver(WorkspaceRepository repository) {
        this.repository = repository;
    }

    Role requireRole(UUID teamId, UUID seasonId, UUID roleId) {
        return repository.findRoleById(roleId)
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROLE_NOT_FOUND",
                        "역할을 찾을 수 없습니다"
                ));
    }
}
