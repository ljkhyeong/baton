package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Routine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineJpaRepository extends JpaRepository<Routine, UUID> {

    List<Routine> findAllBySeasonIdOrderByIdAsc(UUID seasonId);
}
