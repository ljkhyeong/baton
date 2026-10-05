package com.personal.baton.adapter.out.persistence.workspace;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.Season;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceSeasonPersistenceAdapter implements WorkspaceSeasonRepository {

    private final SeasonJpaRepository seasonRepository;

    public WorkspaceSeasonPersistenceAdapter(SeasonJpaRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    @Override
    public Season saveSeason(Season season) {
        try {
            return seasonRepository.saveAndFlush(season);
        } catch (ConcurrencyFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_seasons_team_name")) {
                throw new SeasonNameConflictException(exception);
            }
            if (hasConstraint(exception, "uk_seasons_previous_season")) {
                throw new SeasonSuccessorExistsException(exception);
            }
            if (hasConstraint(exception, "uk_seasons_active_team")) {
                throw new WorkspaceContentConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Season> findSeasonById(UUID seasonId) {
        return seasonRepository.findById(seasonId);
    }

    @Override
    public Optional<Season> findSeasonByTeamIdAndIdWithSharedLock(UUID teamId, UUID seasonId) {
        return WorkspaceConflicts.translate(() -> seasonRepository.findWithSharedLockByTeamIdAndId(teamId, seasonId));
    }

    @Override
    public Optional<Season> findSeasonByTeamIdAndIdForUpdate(UUID teamId, UUID seasonId) {
        return WorkspaceConflicts.translate(() -> seasonRepository.findForUpdateByTeamIdAndId(teamId, seasonId));
    }

    @Override
    public List<Season> findSeasonsByTeamId(UUID teamId) {
        return seasonRepository.findAllByTeamIdOrderByStartDateDescIdDesc(teamId);
    }

    @Override
    public Optional<Season> findLatestSeasonByTeamId(UUID teamId) {
        return seasonRepository.findFirstByTeamIdOrderByStartDateDescIdDesc(teamId);
    }

    @Override
    public List<ScheduledSeasonCandidate> findScheduledSeasonCandidates() {
        return seasonRepository.findScheduledSeasonCandidates();
    }

    @Override
    public Optional<Season> findActiveSeasonByTeamId(UUID teamId) {
        return seasonRepository.findByTeamIdAndEndedAtIsNull(teamId);
    }

    @Override
    public boolean existsSeasonByPreviousSeasonId(UUID previousSeasonId) {
        return seasonRepository.existsByPreviousSeasonId(previousSeasonId);
    }
}
