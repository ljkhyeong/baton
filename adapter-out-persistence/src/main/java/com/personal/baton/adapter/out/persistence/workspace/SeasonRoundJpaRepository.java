package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.SeasonRound;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRoundJpaRepository extends JpaRepository<SeasonRound, UUID> {

    List<SeasonRound> findAllBySeasonIdOrderByMeetingDateAscNameAsc(UUID seasonId);

    boolean existsBySeasonIdAndName(UUID seasonId, String name);
}
