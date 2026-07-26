package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.SeasonRound;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeasonRoundJpaRepository extends JpaRepository<SeasonRound, UUID> {

    List<SeasonRound> findAllBySeasonIdOrderByMeetingDateAscNameAsc(UUID seasonId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select seasonRound from SeasonRound seasonRound where seasonRound.id = :seasonRoundId")
    Optional<SeasonRound> findByIdForUpdate(@Param("seasonRoundId") UUID seasonRoundId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select seasonRound from SeasonRound seasonRound where seasonRound.id = :seasonRoundId")
    Optional<SeasonRound> findByIdWithSharedLock(@Param("seasonRoundId") UUID seasonRoundId);

    boolean existsBySeasonIdAndName(UUID seasonId, String name);

    boolean existsBySeasonIdAndNameAndIdNot(UUID seasonId, String name, UUID seasonRoundId);
}
