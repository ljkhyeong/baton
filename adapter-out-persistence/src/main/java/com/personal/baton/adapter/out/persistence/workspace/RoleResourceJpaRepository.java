package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleResource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleResourceJpaRepository extends JpaRepository<RoleResource, UUID> {

    List<RoleResource> findAllByRoleIdInOrderByRoleIdAscIdAsc(List<UUID> roleIds);

    int countByRoleIdAndArchivedAtIsNull(UUID roleId);

    @Query("""
            select resource from RoleResource resource
            join Role role on role.id = resource.roleId
            where role.teamId = :teamId and role.seasonId = :seasonId
            order by resource.roleId, resource.id
            """)
    List<RoleResource> findAllByTeamIdAndSeasonId(UUID teamId, UUID seasonId);
}
