package com.personal.baton.adapter.out.persistence.workspace;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceOperationsPersistenceAdapter implements WorkspaceOperationsRepository {

    private final RoutineJpaRepository routineRepository;
    private final SeasonRoundJpaRepository seasonRoundRepository;
    private final RoutineExecutionJpaRepository routineExecutionRepository;

    public WorkspaceOperationsPersistenceAdapter(
            RoutineJpaRepository routineRepository,
            SeasonRoundJpaRepository seasonRoundRepository,
            RoutineExecutionJpaRepository routineExecutionRepository
    ) {
        this.routineRepository = routineRepository;
        this.seasonRoundRepository = seasonRoundRepository;
        this.routineExecutionRepository = routineExecutionRepository;
    }

    @Override
    public Routine saveRoutine(Routine routine) {
        return WorkspaceConflicts.translate(() -> routineRepository.saveAndFlush(routine));
    }

    @Override
    public List<Routine> saveRoutines(List<Routine> routines) {
        return WorkspaceConflicts.translate(() -> routineRepository.saveAllAndFlush(routines));
    }

    @Override
    public SeasonRound saveSeasonRound(SeasonRound seasonRound) {
        try {
            return seasonRoundRepository.saveAndFlush(seasonRound);
        } catch (ConcurrencyFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_season_rounds_season_name")) {
                throw new SeasonRoundNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public List<RoutineExecution> saveRoutineExecutions(List<RoutineExecution> routineExecutions) {
        return WorkspaceConflicts.translate(() -> routineExecutionRepository.saveAllAndFlush(routineExecutions));
    }

    @Override
    public RoutineExecution saveRoutineExecution(RoutineExecution routineExecution) {
        return WorkspaceConflicts.translate(() -> routineExecutionRepository.saveAndFlush(routineExecution));
    }

    @Override
    public Optional<Routine> findRoutineById(UUID routineId) {
        return routineRepository.findById(routineId);
    }

    @Override
    public Optional<SeasonRound> findSeasonRoundById(UUID seasonRoundId) {
        return seasonRoundRepository.findById(seasonRoundId);
    }

    @Override
    public Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdForUpdate(
            UUID seasonId,
            UUID seasonRoundId
    ) {
        return WorkspaceConflicts.translate(() -> seasonRoundRepository.findForUpdateBySeasonIdAndId(seasonId, seasonRoundId));
    }

    @Override
    public Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdWithSharedLock(
            UUID seasonId,
            UUID seasonRoundId
    ) {
        return WorkspaceConflicts.translate(() -> seasonRoundRepository.findWithSharedLockBySeasonIdAndId(
                seasonId,
                seasonRoundId
        ));
    }

    @Override
    public Optional<RoutineExecution> findRoutineExecutionById(UUID routineExecutionId) {
        return routineExecutionRepository.findById(routineExecutionId);
    }

    @Override
    public List<Routine> findRoutinesBySeasonId(UUID seasonId) {
        return routineRepository.findAllBySeasonIdOrderByIdAsc(seasonId);
    }

    @Override
    public List<Routine> findActiveRoutinesBySeasonId(UUID seasonId) {
        return routineRepository.findAllBySeasonIdAndArchivedAtIsNullOrderByIdAsc(seasonId);
    }

    @Override
    public boolean existsActiveRoutineWithoutDeadlineRule(UUID seasonId) {
        return routineRepository.existsBySeasonIdAndArchivedAtIsNullAndDeadlineDayOffsetIsNull(seasonId);
    }

    @Override
    public List<Routine> findActiveRoutinesBySeasonIdAndIds(UUID seasonId, List<UUID> routineIds) {
        return routineRepository.findAllBySeasonIdAndIdInAndArchivedAtIsNull(seasonId, routineIds);
    }

    @Override
    public List<SeasonRound> findSeasonRoundsBySeasonId(UUID seasonId) {
        return seasonRoundRepository.findAllBySeasonIdOrderByMeetingDateAscNameAsc(seasonId);
    }

    @Override
    public List<RoutineExecution> findRoutineExecutionsBySeasonRoundIds(List<UUID> seasonRoundIds) {
        return routineExecutionRepository.findAllBySeasonRoundIdInOrderBySeasonRoundIdAscIdAsc(
                seasonRoundIds
        );
    }

    @Override
    public List<RoutineExecution> findPendingDeadlineExecutions(UUID teamId, UUID seasonId, UUID memberId) {
        return routineExecutionRepository.findPendingDeadlineExecutions(teamId, seasonId, memberId);
    }

    @Override
    public List<RoutineExecution> findRoutineExecutionsBySeasonRoundIdWithSharedLock(
            UUID seasonRoundId
    ) {
        return WorkspaceConflicts.translate(() -> routineExecutionRepository
                .findAllWithSharedLockBySeasonRoundIdOrderByIdAsc(seasonRoundId));
    }

    @Override
    public boolean existsSeasonRoundBySeasonId(UUID seasonId) {
        return seasonRoundRepository.existsBySeasonId(seasonId);
    }

    @Override
    public boolean existsSeasonRoundOutsideRange(UUID seasonId, LocalDate startDate, LocalDate endDate) {
        return seasonRoundRepository.existsOutsideRange(seasonId, startDate, endDate);
    }

    @Override
    public boolean existsSeasonRoundBySeasonIdAndName(UUID seasonId, String name) {
        return seasonRoundRepository.existsBySeasonIdAndName(seasonId, name);
    }

    @Override
    public boolean existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
            UUID seasonId,
            LocalDate scheduledOccurrenceDate
    ) {
        return seasonRoundRepository.existsBySeasonIdAndScheduledOccurrenceDate(
                seasonId,
                scheduledOccurrenceDate
        );
    }
}
