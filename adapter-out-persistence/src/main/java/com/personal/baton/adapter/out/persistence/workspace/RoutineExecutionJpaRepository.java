package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoutineExecution;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RoutineExecutionJpaRepository extends JpaRepository<RoutineExecution, UUID> {

    List<RoutineExecution> findAllBySeasonRoundIdInOrderBySeasonRoundIdAscIdAsc(
            List<UUID> seasonRoundIds
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    List<RoutineExecution> findAllWithSharedLockBySeasonRoundIdOrderByIdAsc(
            UUID seasonRoundId
    );
}
