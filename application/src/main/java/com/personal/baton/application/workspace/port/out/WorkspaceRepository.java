package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
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

    SeasonRound saveSeasonRound(SeasonRound seasonRound);

    List<RoutineExecution> saveRoutineExecutions(List<RoutineExecution> routineExecutions);

    RoutineExecution saveRoutineExecution(RoutineExecution routineExecution);

    Decision saveDecision(Decision decision);

    HandoffItem saveHandoffItem(HandoffItem handoffItem);

    RoleResource saveRoleResource(RoleResource roleResource);

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

    Optional<Decision> findDecisionById(UUID decisionId);

    Optional<HandoffItem> findHandoffItemById(UUID itemId);

    Optional<RoleResource> findRoleResourceById(UUID resourceId);

    List<Member> findMembersByTeamId(UUID teamId);

    List<Role> findRolesByTeamId(UUID teamId);

    List<UUID> findExistingRoleIds(UUID teamId, List<UUID> roleIds);

    List<Routine> findRoutinesBySeasonId(UUID seasonId);

    List<SeasonRound> findSeasonRoundsBySeasonId(UUID seasonId);

    List<RoutineExecution> findRoutineExecutionsBySeasonRoundIds(List<UUID> seasonRoundIds);

    List<Decision> findDecisionsBySeasonId(UUID seasonId);

    List<HandoffItem> findHandoffItemsByRoleIds(List<UUID> roleIds);

    List<RoleResource> findRoleResourcesByRoleIds(List<UUID> roleIds);

    boolean existsRoleByTeamIdAndName(UUID teamId, String name);

    boolean existsRoleByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID roleId);

    boolean existsSeasonRoundBySeasonIdAndName(UUID seasonId, String name);

    boolean existsSeasonRoundBySeasonIdAndNameAndIdNot(UUID seasonId, String name, UUID seasonRoundId);
}
