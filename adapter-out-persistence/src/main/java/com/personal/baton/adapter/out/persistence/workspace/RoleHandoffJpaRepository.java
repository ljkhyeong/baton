package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository.TransferredHandoff;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    @Query("""
            select count(handoff) > 0
            from RoleHandoff handoff
            join Role role on role.id = handoff.roleId
            where role.teamId = :teamId and role.seasonId = :seasonId
              and handoff.status in :statuses
              and (handoff.incomingAssignmentStartDate not between :startDate and :endDate
                   or handoff.incomingAssignmentEndDate not between :startDate and :endDate)
            """)
    boolean existsOutsideRangeByStatusIn(
            UUID teamId, UUID seasonId, LocalDate startDate, LocalDate endDate, List<RoleHandoffStatus> statuses
    );

    @Query("""
            select handoff.id as id, handoff.roleId as roleId, role.name as roleName,
                   handoff.transferredAt as transferredAt
            from RoleHandoff handoff
            join Role role on role.id = handoff.roleId
            where role.teamId = :teamId and role.seasonId = :seasonId
              and handoff.seasonId = :seasonId and handoff.toMemberId = :memberId
              and handoff.status = com.personal.baton.domain.workspace.RoleHandoffStatus.TRANSFERRED
            """)
    List<TransferredHandoff> findTransferredHandoffs(UUID teamId, UUID seasonId, UUID memberId);
}
