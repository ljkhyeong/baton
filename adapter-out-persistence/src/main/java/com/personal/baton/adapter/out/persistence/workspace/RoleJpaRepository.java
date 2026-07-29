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
    List<Role> findAllByTeamIdAndSeasonIdOrderByNameAsc(UUID teamId, UUID seasonId);

    @Query("""
            select role.id
            from Role role
            where role.teamId = :teamId
              and role.seasonId = :seasonId
              and role.id in :roleIds
            """)
    List<UUID> findExistingIds(
            @Param("teamId") UUID teamId,
            @Param("seasonId") UUID seasonId,
            @Param("roleIds") List<UUID> roleIds
    );

    boolean existsBySeasonIdAndName(UUID seasonId, String name);

    boolean existsBySeasonIdAndNameAndIdNot(UUID seasonId, String name, UUID roleId);
}
