package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.time.LocalDate;
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

    private static final List<RoleHandoffStatus> OPEN_ROLE_HANDOFF_STATUSES =
            List.of(RoleHandoffStatus.PREPARING, RoleHandoffStatus.TRANSFERRED);

    private final TeamJpaRepository teamRepository;
    private final AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository;
    private final ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository;
    private final SeasonJpaRepository seasonRepository;
    private final MemberJpaRepository memberRepository;
    private final RoleJpaRepository roleRepository;
    private final RoleHandoffJpaRepository roleHandoffRepository;
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
            RoleHandoffJpaRepository roleHandoffRepository,
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
        this.roleHandoffRepository = roleHandoffRepository;
        this.routineRepository = routineRepository;
        this.seasonRoundRepository = seasonRoundRepository;
        this.routineExecutionRepository = routineExecutionRepository;
        this.decisionRepository = decisionRepository;
        this.handoffItemRepository = handoffItemRepository;
        this.roleResourceRepository = roleResourceRepository;
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
    public Season saveSeason(Season season) {
        try {
            return seasonRepository.saveAndFlush(season);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
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
    public Member saveMember(Member member) {
        try {
            return memberRepository.saveAndFlush(member);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_members_team_name")) {
                throw new MemberNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public List<Member> saveMembers(List<Member> members) {
        try {
            return memberRepository.saveAllAndFlush(members);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_members_team_name")) {
                throw new MemberNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Role saveRole(Role role) {
        try {
            return roleRepository.saveAndFlush(role);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_roles_season_name")) {
                throw new RoleNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public RoleHandoff saveRoleHandoff(RoleHandoff roleHandoff) {
        try {
            return roleHandoffRepository.saveAndFlush(roleHandoff);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_role_handoffs_active_role")) {
                throw new WorkspaceContentConflictException(exception);
            }
            throw exception;
        }
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
    public Optional<Team> findTeamById(UUID teamId) {
        return teamRepository.findById(teamId);
    }

    @Override
    public Optional<Team> findTeamByIdWithSharedLock(UUID teamId) {
        try {
            return teamRepository.findByIdWithSharedLock(teamId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceAccessKeyConflictException(exception);
        }
    }

    @Override
    public Optional<Team> findTeamByIdForUpdate(UUID teamId) {
        try {
            return teamRepository.findByIdForUpdate(teamId);
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
    public Optional<Season> findSeasonById(UUID seasonId) {
        return seasonRepository.findById(seasonId);
    }

    @Override
    public Optional<Season> findSeasonByTeamIdAndIdWithSharedLock(UUID teamId, UUID seasonId) {
        try {
            return seasonRepository.findByTeamIdAndIdWithSharedLock(teamId, seasonId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<Season> findSeasonByTeamIdAndIdForUpdate(UUID teamId, UUID seasonId) {
        try {
            return seasonRepository.findByTeamIdAndIdForUpdate(teamId, seasonId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Season> findSeasonsByTeamId(UUID teamId) {
        return seasonRepository.findAllByTeamIdOrderByStartDateDescIdDesc(teamId);
    }

    @Override
    public List<ScheduledSeasonCandidate> findScheduledSeasonCandidates() {
        return seasonRepository.findAllByEndedAtIsNullAndRoundScheduleEnabledTrueOrderByIdAsc()
                .stream()
                .map(season -> new ScheduledSeasonCandidate(season.getTeamId(), season.getId()))
                .toList();
    }

    @Override
    public Optional<Season> findActiveSeasonByTeamId(UUID teamId) {
        return seasonRepository.findByTeamIdAndEndedAtIsNull(teamId);
    }

    @Override
    public boolean existsSeasonByTeamIdAndName(UUID teamId, String name) {
        return seasonRepository.existsByTeamIdAndName(teamId, name);
    }

    @Override
    public boolean existsSeasonByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID seasonId) {
        return seasonRepository.existsByTeamIdAndNameAndIdNot(teamId, name, seasonId);
    }

    @Override
    public boolean existsSeasonByPreviousSeasonId(UUID previousSeasonId) {
        return seasonRepository.existsByPreviousSeasonId(previousSeasonId);
    }

    @Override
    public Optional<Member> findMemberById(UUID memberId) {
        return memberRepository.findById(memberId);
    }

    @Override
    public List<Member> findMembersByTeamIdAndIdsWithSharedLock(
            UUID teamId,
            List<UUID> memberIds
    ) {
        try {
            return memberRepository.findAllByTeamIdAndIdInWithSharedLock(teamId, memberIds);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<Role> findRoleById(UUID roleId) {
        return roleRepository.findById(roleId);
    }

    @Override
    public Optional<Role> findRoleByTeamIdAndSeasonIdAndIdForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId
    ) {
        try {
            return roleRepository.findByTeamIdAndSeasonIdAndIdForUpdate(
                    teamId,
                    seasonId,
                    roleId
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Role> findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
            UUID teamId,
            UUID seasonId,
            List<UUID> roleIds
    ) {
        try {
            return roleRepository.findAllByTeamIdAndSeasonIdAndIdInWithSharedLock(
                    teamId,
                    seasonId,
                    roleIds
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<RoleHandoff> findRoleHandoffById(UUID handoffId) {
        return roleHandoffRepository.findById(handoffId);
    }

    @Override
    public Optional<RoleHandoff> findRoleHandoffByIdForUpdate(UUID handoffId) {
        try {
            return roleHandoffRepository.findByIdForUpdate(handoffId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<RoleHandoff> findOpenRoleHandoffByRoleIdWithSharedLock(UUID roleId) {
        try {
            return roleHandoffRepository.findOpenByRoleIdWithSharedLock(
                    roleId,
                    OPEN_ROLE_HANDOFF_STATUSES
            );
        } catch (PessimisticLockingFailureException exception) {
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
            return seasonRoundRepository.findBySeasonIdAndIdForUpdate(seasonId, seasonRoundId);
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
            return seasonRoundRepository.findBySeasonIdAndIdWithSharedLock(seasonId, seasonRoundId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
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
    public Optional<RoleResource> findRoleResourceByIdWithSharedLock(UUID resourceId) {
        try {
            return roleResourceRepository.findByIdWithSharedLock(resourceId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Member> findMembersByTeamId(UUID teamId) {
        return memberRepository.findAllByTeamIdOrderByNameAsc(teamId);
    }

    @Override
    public List<Role> findRolesByTeamIdAndSeasonId(UUID teamId, UUID seasonId) {
        return roleRepository.findAllByTeamIdAndSeasonIdOrderByNameAsc(teamId, seasonId);
    }

    @Override
    public List<UUID> findExistingRoleIds(UUID teamId, UUID seasonId, List<UUID> roleIds) {
        return roleRepository.findExistingIds(teamId, seasonId, roleIds);
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
            throw new WorkspaceContentConflictException(exception);
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
    public List<RoleHandoff> findRoleHandoffsByRoleIds(List<UUID> roleIds) {
        return roleHandoffRepository.findAllByRoleIdInOrderByRoleIdAscPreparedAtDescIdAsc(
                roleIds
        );
    }

    @Override
    public List<RoleResource> findRoleResourcesByRoleIds(List<UUID> roleIds) {
        return roleResourceRepository.findAllByRoleIdInOrderByRoleIdAscIdAsc(roleIds);
    }

    @Override
    public boolean existsMemberByTeamIdAndName(UUID teamId, String name) {
        return memberRepository.existsByTeamIdAndName(teamId, name);
    }

    @Override
    public boolean existsMemberByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID memberId) {
        return memberRepository.existsByTeamIdAndNameAndIdNot(teamId, name, memberId);
    }

    @Override
    public boolean existsRoleBySeasonIdAndName(UUID seasonId, String name) {
        return roleRepository.existsBySeasonIdAndName(seasonId, name);
    }

    @Override
    public boolean existsRoleBySeasonIdAndNameAndIdNot(UUID seasonId, String name, UUID roleId) {
        return roleRepository.existsBySeasonIdAndNameAndIdNot(seasonId, name, roleId);
    }

    @Override
    public boolean existsOpenRoleHandoffBySeasonId(UUID seasonId) {
        return roleHandoffRepository.existsBySeasonIdAndStatusIn(
                seasonId,
                OPEN_ROLE_HANDOFF_STATUSES
        );
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
