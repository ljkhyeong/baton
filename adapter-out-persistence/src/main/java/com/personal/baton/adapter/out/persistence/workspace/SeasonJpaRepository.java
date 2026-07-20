package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Season;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonJpaRepository extends JpaRepository<Season, UUID> {
}
