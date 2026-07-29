package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Season;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeasonJpaRepository extends JpaRepository<Season, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select season from Season season where season.teamId = :teamId and season.id = :seasonId")
    Optional<Season> findByTeamIdAndIdWithSharedLock(
            @Param("teamId") UUID teamId,
            @Param("seasonId") UUID seasonId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select season from Season season where season.teamId = :teamId and season.id = :seasonId")
    Optional<Season> findByTeamIdAndIdForUpdate(
            @Param("teamId") UUID teamId,
            @Param("seasonId") UUID seasonId
    );

    List<Season> findAllByTeamIdOrderByStartDateDescIdDesc(UUID teamId);

    Optional<Season> findByTeamIdAndEndedAtIsNull(UUID teamId);

    boolean existsByTeamIdAndName(UUID teamId, String name);

    boolean existsByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID seasonId);

    boolean existsByPreviousSeasonId(UUID previousSeasonId);
}
