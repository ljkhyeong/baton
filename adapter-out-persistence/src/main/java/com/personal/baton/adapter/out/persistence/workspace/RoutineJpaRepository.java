package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Routine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineJpaRepository extends JpaRepository<Routine, UUID> {

    List<Routine> findAllBySeasonIdOrderByIdAsc(UUID seasonId);

    List<Routine> findAllBySeasonIdAndArchivedAtIsNullOrderByIdAsc(UUID seasonId);

    // 마감 일수와 시각은 DB 제약으로 함께 설정하거나 함께 비운다.
    boolean existsBySeasonIdAndArchivedAtIsNullAndDeadlineDayOffsetIsNull(UUID seasonId);

    List<Routine> findAllBySeasonIdAndIdInAndArchivedAtIsNull(UUID seasonId, List<UUID> routineIds);
}
