package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.Season;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SeasonJpaRepository extends JpaRepository<Season, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Season> findWithSharedLockByTeamIdAndId(
            UUID teamId,
            UUID seasonId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Season> findForUpdateByTeamIdAndId(
            UUID teamId,
            UUID seasonId
    );

    List<Season> findAllByTeamIdOrderByStartDateDescIdDesc(UUID teamId);

    Optional<Season> findFirstByTeamIdOrderByStartDateDescIdDesc(UUID teamId);

    Optional<Season> findByTeamIdAndEndedAtIsNull(UUID teamId);

    @Query("""
            select season.teamId, season.id
            from Season season
            where season.endedAt is null and season.roundSchedule.enabled = true
            order by season.id
            """)
    List<ScheduledSeasonCandidate> findScheduledSeasonCandidates();

    boolean existsByPreviousSeasonId(UUID previousSeasonId);
}
