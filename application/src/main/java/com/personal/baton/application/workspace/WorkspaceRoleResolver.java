package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import java.util.UUID;
import java.util.function.Supplier;

@Component
final class WorkspaceRoleResolver {

    private final WorkspacePeopleRepository repository;
    private final WorkspaceRecordsRepository recordsRepository;

    WorkspaceRoleResolver(WorkspacePeopleRepository repository, WorkspaceRecordsRepository recordsRepository) {
        this.repository = repository;
        this.recordsRepository = recordsRepository;
    }

    Role requireRole(UUID teamId, UUID seasonId, UUID roleId) {
        return requireRole(teamId, seasonId, roleId, WorkspaceNotFoundException::role);
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
                .orElseThrow(WorkspaceNotFoundException::role);
    }

    // 자료는 소속 역할로 시즌을 판단한다. 다른 시즌 역할의 자료도 이 시즌에서는 없는 자료로 본다.
    RoleResource requireRoleResource(UUID teamId, UUID seasonId, UUID resourceId) {
        RoleResource resource = recordsRepository.findRoleResourceById(resourceId)
                .orElseThrow(WorkspaceNotFoundException::roleResource);
        requireRole(teamId, seasonId, resource.getRoleId(), WorkspaceNotFoundException::roleResource);
        return resource;
    }
}
