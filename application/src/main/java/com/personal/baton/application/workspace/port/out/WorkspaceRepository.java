package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {

    Team saveTeam(Team team);

    AccessKeyChangeHistory saveAccessKeyChangeHistory(AccessKeyChangeHistory history);

    ContentCreationIdempotency saveContentCreationIdempotency(ContentCreationIdempotency idempotency);

    Season saveSeason(Season season);

    List<Member> saveMembers(List<Member> members);

    Role saveRole(Role role);

    Routine saveRoutine(Routine routine);

    Decision saveDecision(Decision decision);

    HandoffItem saveHandoffItem(HandoffItem handoffItem);

    Optional<Team> findTeamById(UUID teamId);

    Optional<Team> findTeamByIdempotencyKeyHash(String idempotencyKeyHash);

    Optional<ContentCreationIdempotency> findContentCreationIdempotency(
            UUID teamId,
            String idempotencyHash
    );

    boolean existsAccessKeyChangeHistory(UUID teamId, String idempotencyHash);

    Optional<Season> findSeasonById(UUID seasonId);

    Optional<Member> findMemberById(UUID memberId);

    Optional<Role> findRoleById(UUID roleId);

    Optional<Routine> findRoutineById(UUID routineId);

    Optional<Decision> findDecisionById(UUID decisionId);

    Optional<HandoffItem> findHandoffItemById(UUID itemId);

    List<Member> findMembersByTeamId(UUID teamId);

    List<Role> findRolesByTeamId(UUID teamId);

    List<UUID> findExistingRoleIds(UUID teamId, List<UUID> roleIds);

    List<Routine> findRoutinesBySeasonId(UUID seasonId);

    List<Decision> findDecisionsBySeasonId(UUID seasonId);

    List<HandoffItem> findHandoffItemsByRoleIds(List<UUID> roleIds);

    boolean existsRoleByTeamIdAndName(UUID teamId, String name);
}
