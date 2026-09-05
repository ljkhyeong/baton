package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.RoleResource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRecordsPersistenceAdapter implements WorkspaceRecordsRepository {

    private final DecisionJpaRepository decisionRepository;
    private final HandoffItemJpaRepository handoffItemRepository;
    private final RoleResourceJpaRepository roleResourceRepository;

    public WorkspaceRecordsPersistenceAdapter(
            DecisionJpaRepository decisionRepository,
            HandoffItemJpaRepository handoffItemRepository,
            RoleResourceJpaRepository roleResourceRepository
    ) {
        this.decisionRepository = decisionRepository;
        this.handoffItemRepository = handoffItemRepository;
        this.roleResourceRepository = roleResourceRepository;
    }

    @Override
    public Decision saveDecision(Decision decision) {
        try {
            return decisionRepository.saveAndFlush(decision);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public HandoffItem saveHandoffItem(HandoffItem handoffItem) {
        try {
            return handoffItemRepository.saveAndFlush(handoffItem);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public RoleResource saveRoleResource(RoleResource roleResource) {
        try {
            return roleResourceRepository.saveAndFlush(roleResource);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<Decision> findDecisionById(UUID decisionId) {
        return decisionRepository.findById(decisionId);
    }

    @Override
    public Optional<HandoffItem> findHandoffItemById(UUID itemId) {
        return handoffItemRepository.findById(itemId);
    }

    @Override
    public Optional<RoleResource> findRoleResourceById(UUID resourceId) {
        return roleResourceRepository.findById(resourceId);
    }

    @Override
    public List<Decision> findDecisionsBySeasonId(UUID seasonId) {
        return decisionRepository.findAllBySeasonIdOrderByCreatedAtDescIdDesc(seasonId);
    }

    @Override
    public List<HandoffItem> findHandoffItemsByRoleIds(List<UUID> roleIds) {
        return handoffItemRepository.findAllByRoleIdInOrderByIdAsc(roleIds);
    }

    @Override
    public List<RoleResource> findRoleResourcesByRoleIds(List<UUID> roleIds) {
        return roleResourceRepository.findAllByRoleIdInOrderByRoleIdAscIdAsc(roleIds);
    }

    @Override
    public List<RoleResource> findRoleResourcesByTeamIdAndSeasonId(UUID teamId, UUID seasonId) {
        return roleResourceRepository.findAllByTeamIdAndSeasonId(teamId, seasonId);
    }
}
