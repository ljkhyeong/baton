package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.Role;
import java.util.UUID;
import java.util.function.Supplier;

@Component
final class WorkspaceRoleResolver {

    private final WorkspacePeopleRepository repository;

    WorkspaceRoleResolver(WorkspacePeopleRepository repository) {
        this.repository = repository;
    }

    Role requireRole(UUID teamId, UUID seasonId, UUID roleId) {
        return requireRole(
                teamId,
                seasonId,
                roleId,
                () -> new WorkspaceNotFoundException(
                        "ROLE_NOT_FOUND",
                        "역할을 찾을 수 없습니다"
                )
        );
    }

    Role requireRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            Supplier<? extends RuntimeException> notFound
    ) {
        return repository.findRoleById(roleId)
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(notFound);
    }

    Role requireRoleForUpdate(UUID teamId, UUID seasonId, UUID roleId) {
        return repository.findRoleByTeamIdAndSeasonIdAndIdForUpdate(teamId, seasonId, roleId)
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROLE_NOT_FOUND",
                        "역할을 찾을 수 없습니다"
                ));
    }
}
