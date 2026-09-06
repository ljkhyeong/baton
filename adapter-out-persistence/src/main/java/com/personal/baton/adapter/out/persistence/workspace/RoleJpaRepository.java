package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Role;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleJpaRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "responsibilities")
    List<Role> findAllByTeamIdAndSeasonIdOrderByNameAsc(UUID teamId, UUID seasonId);

    @EntityGraph(attributePaths = "responsibilities")
    List<Role> findAllByTeamIdAndSeasonIdAndIdIn(UUID teamId, UUID seasonId, List<UUID> roleIds);

    @Query("""
            select count(role) > 0
            from Role role
            where role.teamId = :teamId and role.seasonId = :seasonId
              and (role.assignmentStartDate not between :startDate and :endDate
                   or role.assignmentEndDate not between :startDate and :endDate)
            """)
    boolean existsAssignmentOutsideRange(UUID teamId, UUID seasonId, LocalDate startDate, LocalDate endDate);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Role> findForUpdateByTeamIdAndSeasonIdAndId(
            UUID teamId,
            UUID seasonId,
            UUID roleId
    );

    @Query("""
            select role.name
            from Role role
            where role.teamId = :teamId
              and role.seasonId = :seasonId
              and role.id in :roleIds
            """)
    List<String> findNamesByTeamIdAndSeasonIdAndIdIn(UUID teamId, UUID seasonId, List<UUID> roleIds);

    @Lock(LockModeType.PESSIMISTIC_READ)
    List<Role> findAllWithSharedLockByTeamIdAndSeasonIdAndIdInOrderByIdAsc(
            UUID teamId,
            UUID seasonId,
            List<UUID> roleIds
    );

}
