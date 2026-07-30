package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleHandoffJpaRepository extends JpaRepository<RoleHandoff, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select handoff from RoleHandoff handoff where handoff.id = :handoffId")
    Optional<RoleHandoff> findByIdForUpdate(@Param("handoffId") UUID handoffId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select handoff
            from RoleHandoff handoff
            where handoff.roleId = :roleId
              and handoff.status in :statuses
            """)
    Optional<RoleHandoff> findOpenByRoleIdWithSharedLock(
            @Param("roleId") UUID roleId,
            @Param("statuses") List<RoleHandoffStatus> statuses
    );

    List<RoleHandoff> findAllByRoleIdInOrderByRoleIdAscPreparedAtDescIdAsc(
            List<UUID> roleIds
    );

    boolean existsBySeasonIdAndStatusIn(
            UUID seasonId,
            List<RoleHandoffStatus> statuses
    );
}
