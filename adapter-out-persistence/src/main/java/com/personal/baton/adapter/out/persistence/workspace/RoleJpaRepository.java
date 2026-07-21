package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleJpaRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "responsibilities")
    List<Role> findAllByTeamIdOrderByNameAsc(UUID teamId);

    @Query("select role.id from Role role where role.teamId = :teamId and role.id in :roleIds")
    List<UUID> findExistingIds(
            @Param("teamId") UUID teamId,
            @Param("roleIds") List<UUID> roleIds
    );

    boolean existsByTeamIdAndName(UUID teamId, String name);

    boolean existsByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID roleId);
}
