package com.personal.baton.adapter.out.persistence.workspace;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Team;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceAccessPersistenceAdapter implements WorkspaceAccessRepository {

    private final TeamJpaRepository teamRepository;
    private final AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository;
    private final ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository;

    public WorkspaceAccessPersistenceAdapter(
            TeamJpaRepository teamRepository,
            AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository,
            ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository
    ) {
        this.teamRepository = teamRepository;
        this.accessKeyChangeHistoryRepository = accessKeyChangeHistoryRepository;
        this.contentCreationIdempotencyRepository = contentCreationIdempotencyRepository;
    }

    @Override
    public Team saveTeam(Team team) {
        boolean creatingWorkspace = team.getVersion() == null;
        try {
            return teamRepository.saveAndFlush(team);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceAccessKeyConflictException(exception);
        } catch (PessimisticLockingFailureException exception) {
            if (creatingWorkspace) {
                throw new IdempotencyKeyConflictException(exception);
            }
            throw new WorkspaceAccessKeyConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_teams_idempotency_key_hash")) {
                throw new IdempotencyKeyConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public AccessKeyChangeHistory saveAccessKeyChangeHistory(AccessKeyChangeHistory history) {
        return accessKeyChangeHistoryRepository.saveAndFlush(history);
    }

    @Override
    public ContentCreationIdempotency saveContentCreationIdempotency(ContentCreationIdempotency idempotency) {
        try {
            return contentCreationIdempotencyRepository.saveAndFlush(idempotency);
        } catch (PessimisticLockingFailureException exception) {
            throw new IdempotencyKeyConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_content_creation_idempotency_team_hash")) {
                throw new IdempotencyKeyConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Team> findTeamById(UUID teamId) {
        return teamRepository.findById(teamId);
    }

    @Override
    public Optional<Team> findTeamByIdWithSharedLock(UUID teamId) {
        try {
            return teamRepository.findWithSharedLockById(teamId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceAccessKeyConflictException(exception);
        }
    }

    @Override
    public Optional<Team> findTeamByIdForUpdate(UUID teamId) {
        try {
            return teamRepository.findForUpdateById(teamId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<Team> findTeamByIdempotencyKeyHash(String idempotencyKeyHash) {
        return teamRepository.findByIdempotencyKeyHash(idempotencyKeyHash);
    }

    @Override
    public Optional<ContentCreationIdempotency> findContentCreationIdempotency(
            UUID teamId,
            String idempotencyHash
    ) {
        return contentCreationIdempotencyRepository.findByTeamIdAndIdempotencyHash(teamId, idempotencyHash);
    }

    @Override
    public boolean existsAccessKeyChangeHistory(UUID teamId, String idempotencyHash) {
        return accessKeyChangeHistoryRepository.existsByTeamIdAndIdempotencyHash(teamId, idempotencyHash);
    }

    @Override
    public Set<String> findAccessKeyChangeIdempotencyHashes(
            UUID teamId,
            List<String> idempotencyHashes
    ) {
        return accessKeyChangeHistoryRepository.findIdempotencyHashes(
                teamId,
                idempotencyHashes
        );
    }
}

