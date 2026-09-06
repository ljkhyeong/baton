package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.SeasonRound;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SeasonRoundJpaRepository extends JpaRepository<SeasonRound, UUID> {

    List<SeasonRound> findAllBySeasonIdOrderByMeetingDateAscNameAsc(UUID seasonId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SeasonRound> findForUpdateBySeasonIdAndId(
            UUID seasonId,
            UUID seasonRoundId
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<SeasonRound> findWithSharedLockBySeasonIdAndId(
            UUID seasonId,
            UUID seasonRoundId
    );

    boolean existsBySeasonId(UUID seasonId);

    @Query("""
            select count(round) > 0
            from SeasonRound round
            where round.seasonId = :seasonId
              and round.meetingDate not between :startDate and :endDate
            """)
    boolean existsOutsideRange(UUID seasonId, LocalDate startDate, LocalDate endDate);

    boolean existsBySeasonIdAndName(UUID seasonId, String name);

    boolean existsBySeasonIdAndScheduledOccurrenceDate(UUID seasonId, LocalDate scheduledOccurrenceDate);
}
