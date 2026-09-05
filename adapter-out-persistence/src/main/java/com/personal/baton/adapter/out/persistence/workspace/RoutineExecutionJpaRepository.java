package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoutineExecution;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoutineExecutionJpaRepository extends JpaRepository<RoutineExecution, UUID> {

    List<RoutineExecution> findAllBySeasonRoundIdInOrderBySeasonRoundIdAscIdAsc(
            List<UUID> seasonRoundIds
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    List<RoutineExecution> findAllWithSharedLockBySeasonRoundIdOrderByIdAsc(
            UUID seasonRoundId
    );

    @Query("""
            select execution from RoutineExecution execution
            join SeasonRound seasonRound on seasonRound.id = execution.seasonRoundId
            join Role role on role.id = execution.ownerRoleId
            where seasonRound.seasonId = :seasonId and seasonRound.archivedAt is null
              and role.teamId = :teamId and role.seasonId = :seasonId
              and role.currentMemberId = :memberId
              and execution.status <> com.personal.baton.domain.workspace.RoutineStatus.DONE
              and execution.deadlineAt is not null
            """)
    List<RoutineExecution> findPendingDeadlineExecutions(UUID teamId, UUID seasonId, UUID memberId);
}
