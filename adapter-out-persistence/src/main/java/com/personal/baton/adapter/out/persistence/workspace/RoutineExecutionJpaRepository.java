package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoutineExecution;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineExecutionJpaRepository extends JpaRepository<RoutineExecution, UUID> {

    List<RoutineExecution> findAllBySeasonRoundIdInOrderBySeasonRoundIdAscIdAsc(
            List<UUID> seasonRoundIds
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select routineExecution
            from RoutineExecution routineExecution
            where routineExecution.seasonRoundId = :seasonRoundId
            order by routineExecution.id asc
            """)
    List<RoutineExecution> findAllBySeasonRoundIdWithSharedLock(
            @Param("seasonRoundId") UUID seasonRoundId
    );
}
