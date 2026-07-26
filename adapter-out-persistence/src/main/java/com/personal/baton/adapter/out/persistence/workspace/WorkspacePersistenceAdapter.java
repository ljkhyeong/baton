package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspacePersistenceAdapter implements WorkspaceRepository {

    private final TeamJpaRepository teamRepository;
    private final AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository;
    private final ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository;
    private final SeasonJpaRepository seasonRepository;
    private final MemberJpaRepository memberRepository;
    private final RoleJpaRepository roleRepository;
    private final RoutineJpaRepository routineRepository;
    private final SeasonRoundJpaRepository seasonRoundRepository;
    private final RoutineExecutionJpaRepository routineExecutionRepository;
    private final DecisionJpaRepository decisionRepository;
    private final HandoffItemJpaRepository handoffItemRepository;
    private final RoleResourceJpaRepository roleResourceRepository;

    public WorkspacePersistenceAdapter(
            TeamJpaRepository teamRepository,
            AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository,
            ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository,
            SeasonJpaRepository seasonRepository,
            MemberJpaRepository memberRepository,
            RoleJpaRepository roleRepository,
            RoutineJpaRepository routineRepository,
            SeasonRoundJpaRepository seasonRoundRepository,
            RoutineExecutionJpaRepository routineExecutionRepository,
            DecisionJpaRepository decisionRepository,
            HandoffItemJpaRepository handoffItemRepository,
            RoleResourceJpaRepository roleResourceRepository
    ) {
        this.teamRepository = teamRepository;
        this.accessKeyChangeHistoryRepository = accessKeyChangeHistoryRepository;
        this.contentCreationIdempotencyRepository = contentCreationIdempotencyRepository;
        this.seasonRepository = seasonRepository;
        this.memberRepository = memberRepository;
        this.roleRepository = roleRepository;
        this.routineRepository = routineRepository;
        this.seasonRoundRepository = seasonRoundRepository;
        this.routineExecutionRepository = routineExecutionRepository;
        this.decisionRepository = decisionRepository;
        this.handoffItemRepository = handoffItemRepository;
        this.roleResourceRepository = roleResourceRepository;
    }

    @Override
    public Team saveTeam(Team team) {
        try {
            return teamRepository.saveAndFlush(team);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceAccessKeyConflictException();
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_teams_idempotency_key_hash")) {
                throw new IdempotencyKeyConflictException();
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
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_content_creation_idempotency_team_hash")) {
                throw new IdempotencyKeyConflictException();
            }
            throw exception;
        }
    }

    @Override
    public Season saveSeason(Season season) {
        return seasonRepository.save(season);
    }

    @Override
    public List<Member> saveMembers(List<Member> members) {
        return memberRepository.saveAll(members);
    }

    @Override
    public Role saveRole(Role role) {
        try {
            return roleRepository.saveAndFlush(role);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_roles_team_name")) {
                throw new RoleNameConflictException();
            }
            throw exception;
        }
    }

    @Override
    public Routine saveRoutine(Routine routine) {
        try {
            return routineRepository.saveAndFlush(routine);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public SeasonRound saveSeasonRound(SeasonRound seasonRound) {
        try {
            return seasonRoundRepository.saveAndFlush(seasonRound);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_season_rounds_season_name")) {
                throw new SeasonRoundNameConflictException();
            }
            throw exception;
        }
    }

    @Override
    public List<RoutineExecution> saveRoutineExecutions(List<RoutineExecution> routineExecutions) {
        return routineExecutionRepository.saveAllAndFlush(routineExecutions);
    }

    @Override
    public RoutineExecution saveRoutineExecution(RoutineExecution routineExecution) {
        try {
            return routineExecutionRepository.saveAndFlush(routineExecution);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public Decision saveDecision(Decision decision) {
        try {
            return decisionRepository.saveAndFlush(decision);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public HandoffItem saveHandoffItem(HandoffItem handoffItem) {
        try {
            return handoffItemRepository.saveAndFlush(handoffItem);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public RoleResource saveRoleResource(RoleResource roleResource) {
        try {
            return roleResourceRepository.saveAndFlush(roleResource);
        } catch (OptimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public Optional<Team> findTeamById(UUID teamId) {
        return teamRepository.findById(teamId);
    }

    @Override
    public Optional<Team> findTeamByIdWithSharedLock(UUID teamId) {
        try {
            return teamRepository.findByIdWithSharedLock(teamId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceAccessKeyConflictException();
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
    public Optional<Season> findSeasonById(UUID seasonId) {
        return seasonRepository.findById(seasonId);
    }

    @Override
    public Optional<Member> findMemberById(UUID memberId) {
        return memberRepository.findById(memberId);
    }

    @Override
    public Optional<Role> findRoleById(UUID roleId) {
        return roleRepository.findById(roleId);
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
            return seasonRoundRepository.findBySeasonIdAndIdForUpdate(seasonId, seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public Optional<SeasonRound> findSeasonRoundBySeasonIdAndIdWithSharedLock(
            UUID seasonId,
            UUID seasonRoundId
    ) {
        try {
            return seasonRoundRepository.findBySeasonIdAndIdWithSharedLock(seasonId, seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public Optional<RoutineExecution> findRoutineExecutionById(UUID routineExecutionId) {
        return routineExecutionRepository.findById(routineExecutionId);
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
    public List<Member> findMembersByTeamId(UUID teamId) {
        return memberRepository.findAllByTeamIdOrderByNameAsc(teamId);
    }

    @Override
    public List<Role> findRolesByTeamId(UUID teamId) {
        return roleRepository.findAllByTeamIdOrderByNameAsc(teamId);
    }

    @Override
    public List<UUID> findExistingRoleIds(UUID teamId, List<UUID> roleIds) {
        return roleRepository.findExistingIds(teamId, roleIds);
    }

    @Override
    public List<Routine> findRoutinesBySeasonId(UUID seasonId) {
        return routineRepository.findAllBySeasonIdOrderByIdAsc(seasonId);
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
    public List<RoutineExecution> findRoutineExecutionsBySeasonRoundIdWithSharedLock(
            UUID seasonRoundId
    ) {
        try {
            return routineExecutionRepository.findAllBySeasonRoundIdWithSharedLock(seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException();
        }
    }

    @Override
    public List<Decision> findDecisionsBySeasonId(UUID seasonId) {
        return decisionRepository.findAllBySeasonIdOrderByCreatedAtDesc(seasonId);
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
    public boolean existsRoleByTeamIdAndName(UUID teamId, String name) {
        return roleRepository.existsByTeamIdAndName(teamId, name);
    }

    @Override
    public boolean existsRoleByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID roleId) {
        return roleRepository.existsByTeamIdAndNameAndIdNot(teamId, name, roleId);
    }

    @Override
    public boolean existsSeasonRoundBySeasonIdAndName(UUID seasonId, String name) {
        return seasonRoundRepository.existsBySeasonIdAndName(seasonId, name);
    }

    @Override
    public boolean existsSeasonRoundBySeasonIdAndNameAndIdNot(
            UUID seasonId,
            String name,
            UUID seasonRoundId
    ) {
        return seasonRoundRepository.existsBySeasonIdAndNameAndIdNot(seasonId, name, seasonRoundId);
    }

    private boolean hasConstraint(Throwable throwable, String expectedName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                String actualName = constraintViolation.getConstraintName()
                        .replace("`", "")
                        .toLowerCase(Locale.ROOT);
                String normalizedExpectedName = expectedName.toLowerCase(Locale.ROOT);
                if (actualName.equals(normalizedExpectedName)
                        || actualName.endsWith("." + normalizedExpectedName)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
