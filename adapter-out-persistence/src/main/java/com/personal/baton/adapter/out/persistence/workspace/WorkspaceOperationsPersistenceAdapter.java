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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
        try {
            return routineRepository.saveAndFlush(routine);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Routine> saveRoutines(List<Routine> routines) {
        try {
            return routineRepository.saveAllAndFlush(routines);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public SeasonRound saveSeasonRound(SeasonRound seasonRound) {
        try {
            return seasonRoundRepository.saveAndFlush(seasonRound);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
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
        try {
            return routineExecutionRepository.saveAllAndFlush(routineExecutions);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public RoutineExecution saveRoutineExecution(RoutineExecution routineExecution) {
        try {
            return routineExecutionRepository.saveAndFlush(routineExecution);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
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
        try {
            return seasonRoundRepository.findForUpdateBySeasonIdAndId(seasonId, seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdWithSharedLock(
            UUID seasonId,
            UUID seasonRoundId
    ) {
        try {
            return seasonRoundRepository.findWithSharedLockBySeasonIdAndId(
                    seasonId,
                    seasonRoundId
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
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
        try {
            return routineExecutionRepository
                    .findAllWithSharedLockBySeasonRoundIdOrderByIdAsc(seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public boolean existsSeasonRoundBySeasonId(UUID seasonId) {
        return seasonRoundRepository.existsBySeasonId(seasonId);
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
