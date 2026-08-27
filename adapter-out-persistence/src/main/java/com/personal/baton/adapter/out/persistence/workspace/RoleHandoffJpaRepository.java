package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RoleHandoffJpaRepository extends JpaRepository<RoleHandoff, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RoleHandoff> findForUpdateById(UUID handoffId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<RoleHandoff> findOpenWithSharedLockByRoleIdAndStatusIn(
            UUID roleId,
            List<RoleHandoffStatus> statuses
    );

    List<RoleHandoff> findAllByRoleIdInOrderByRoleIdAscPreparedAtDescIdAsc(
            List<UUID> roleIds
    );

    boolean existsBySeasonIdAndStatusIn(
            UUID seasonId,
            List<RoleHandoffStatus> statuses
    );
}
