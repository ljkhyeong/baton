package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceOperationsRepository {

    Routine saveRoutine(Routine routine);

    List<Routine> saveRoutines(List<Routine> routines);

    SeasonRound saveSeasonRound(SeasonRound seasonRound);

    List<RoutineExecution> saveRoutineExecutions(List<RoutineExecution> routineExecutions);

    RoutineExecution saveRoutineExecution(RoutineExecution routineExecution);

    Optional<Routine> findRoutineById(UUID routineId);

    Optional<SeasonRound> findSeasonRoundById(UUID seasonRoundId);

    Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdForUpdate(
            UUID seasonId,
            UUID seasonRoundId
    );

    Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdWithSharedLock(
            UUID seasonId,
            UUID seasonRoundId
    );

    Optional<RoutineExecution> findRoutineExecutionById(UUID routineExecutionId);

    List<Routine> findRoutinesBySeasonId(UUID seasonId);

    List<Routine> findActiveRoutinesBySeasonId(UUID seasonId);

    boolean existsActiveRoutineWithoutDeadlineRule(UUID seasonId);

    List<Routine> findActiveRoutinesBySeasonIdAndIds(UUID seasonId, List<UUID> routineIds);

    List<SeasonRound> findSeasonRoundsBySeasonId(UUID seasonId);

    List<RoutineExecution> findRoutineExecutionsBySeasonRoundIds(List<UUID> seasonRoundIds);

    List<RoutineExecution> findPendingDeadlineExecutions(UUID teamId, UUID seasonId, UUID memberId);

    List<RoutineExecution> findRoutineExecutionsBySeasonRoundIdWithSharedLock(UUID seasonRoundId);

    boolean existsSeasonRoundBySeasonId(UUID seasonId);

    boolean existsSeasonRoundOutsideRange(UUID seasonId, LocalDate startDate, LocalDate endDate);

    boolean existsSeasonRoundBySeasonIdAndName(UUID seasonId, String name);

    boolean existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
            UUID seasonId,
            LocalDate scheduledOccurrenceDate
    );
}
