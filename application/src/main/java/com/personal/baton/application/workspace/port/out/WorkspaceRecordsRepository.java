package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.RoleResource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRecordsRepository {

    Decision saveDecision(Decision decision);

    HandoffItem saveHandoffItem(HandoffItem handoffItem);

    RoleResource saveRoleResource(RoleResource roleResource);

    Optional<Decision> findDecisionById(UUID decisionId);

    Optional<HandoffItem> findHandoffItemById(UUID itemId);

    Optional<RoleResource> findRoleResourceById(UUID resourceId);

    List<Decision> findDecisionsBySeasonId(UUID seasonId);

    List<HandoffItem> findHandoffItemsByRoleIds(List<UUID> roleIds);

    List<RoleResource> findRoleResourcesByRoleIds(List<UUID> roleIds);

    List<RoleResource> findRoleResourcesByTeamIdAndSeasonId(UUID teamId, UUID seasonId);
}
