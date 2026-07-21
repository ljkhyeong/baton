package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoutineExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineExecutionJpaRepository extends JpaRepository<RoutineExecution, UUID> {

    List<RoutineExecution> findAllBySeasonRoundIdInOrderBySeasonRoundIdAscIdAsc(
            List<UUID> seasonRoundIds
    );
}
