package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Decision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionJpaRepository extends JpaRepository<Decision, UUID> {

    @EntityGraph(attributePaths = "roleIds")
    List<Decision> findAllBySeasonIdOrderByCreatedAtDescIdDesc(UUID seasonId);
}
