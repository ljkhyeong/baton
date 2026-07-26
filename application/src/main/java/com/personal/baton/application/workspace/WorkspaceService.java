package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService implements WorkspaceUseCase {

    private static final String[] MEMBER_TONES = {
            "#d9e4da", "#f1d6cc", "#d8dfee", "#eee3bf", "#dce7ef", "#eadcf0"
    };
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{32,200}");
    private static final String IDEMPOTENCY_HASH_DOMAIN = "baton:workspace-idempotency:v1";
    private static final String ACCESS_KEY_DERIVATION_DOMAIN = "baton:workspace-access:v1";
    private static final String REQUEST_FINGERPRINT_DOMAIN = "baton:workspace-request:v1";
    private static final String ROTATE_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-access-key-rotate-idempotency:v1";
    private static final String ROTATE_ACCESS_KEY_DERIVATION_DOMAIN =
            "baton:workspace-access-key-rotate:v1";
    private static final String RECOVER_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-access-key-recover-idempotency:v1";
    private static final String RECOVER_ACCESS_KEY_DERIVATION_DOMAIN =
            "baton:workspace-access-key-recover:v1";
    private static final String CONTENT_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-content-idempotency:v1";
    private static final String CONTENT_REQUEST_FINGERPRINT_DOMAIN =
            "baton:workspace-content-request:v1";

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final String workspaceCreationKey;
    private final String workspaceRecoveryKey;

    public WorkspaceService(
            WorkspaceRepository repository,
            Clock clock,
            @Value("${baton.workspace.creation-key:}") String workspaceCreationKey,
            @Value("${baton.workspace.recovery-key:}") String workspaceRecoveryKey
    ) {
        this.repository = repository;
        this.clock = clock;
        this.workspaceCreationKey = workspaceCreationKey == null ? "" : workspaceCreationKey;
        this.workspaceRecoveryKey = workspaceRecoveryKey == null ? "" : workspaceRecoveryKey;
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    ) {
        requireValidIdempotencyKey(idempotencyKey);
        verifyWorkspaceCreationPermission(creationKey);

        String accessKey = deriveInitialAccessKey(idempotencyKey);
        String idempotencyKeyHash = hashDomainValueHex(IDEMPOTENCY_HASH_DOMAIN, idempotencyKey);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();

        Team team = Team.create(teamId, command.teamName(), hashAccessKeyHex(accessKey));
        Season season = Season.create(
                seasonId,
                teamId,
                command.seasonName(),
                command.startDate(),
                command.endDate()
        );
        List<Member> members = createMembers(teamId, command.memberNames());
        String requestFingerprint = fingerprintCreationRequest(team, season, members);

        Team existing = repository.findTeamByIdempotencyKeyHash(idempotencyKeyHash).orElse(null);
        if (existing != null) {
            if (!requestFingerprint.equals(existing.getCreationRequestFingerprint())) {
                throw new IdempotencyKeyReusedException();
            }
            if (!matchesAccessKey(existing, accessKey)) {
                throw new IdempotencyReplayExpiredException();
            }
            Season existingSeason = repository.findSeasonById(existing.getCreationSeasonId())
                    .filter(found -> found.getTeamId().equals(existing.getId()))
                    .orElseThrow(() -> new IllegalStateException("멱등 생성된 팀의 생성 시즌을 찾을 수 없습니다"));
            return new CreatedWorkspaceResult(existing.getId(), existingSeason.getId(), accessKey);
        }

        team.recordCreationRequest(idempotencyKeyHash, requestFingerprint, seasonId);
        repository.saveTeam(team);
        repository.saveSeason(season);
        repository.saveMembers(members);
        return new CreatedWorkspaceResult(teamId, seasonId, accessKey);
    }

    @Override
    @Transactional
    public AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    ) {
        requireValidIdempotencyKey(idempotencyKey);
        AuthorizedScope scope = requireScope(teamId, seasonId);
        AccessKeyChange change = deriveAccessKeyChange(
                ROTATE_IDEMPOTENCY_HASH_DOMAIN,
                ROTATE_ACCESS_KEY_DERIVATION_DOMAIN,
                teamId,
                seasonId,
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        verifyAccessKey(scope.team(), currentAccessKey);
        return replaceAccessKey(scope.team(), change);
    }

    @Override
    @Transactional
    public AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    ) {
        verifyWorkspaceRecoveryPermission(recoveryKey);
        requireValidIdempotencyKey(idempotencyKey);
        AuthorizedScope scope = requireScope(teamId, seasonId);
        AccessKeyChange change = deriveAccessKeyChange(
                RECOVER_IDEMPOTENCY_HASH_DOMAIN,
                RECOVER_ACCESS_KEY_DERIVATION_DOMAIN,
                teamId,
                seasonId,
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        return replaceAccessKey(scope.team(), change);
    }

    @Override
    public WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey) {
        AuthorizedScope scope = authorize(teamId, seasonId, accessKey);
        List<Member> members = repository.findMembersByTeamId(teamId);
        List<Role> roles = repository.findRolesByTeamId(teamId);
        List<Routine> routines = repository.findRoutinesBySeasonId(seasonId);
        List<SeasonRound> rounds = repository.findSeasonRoundsBySeasonId(seasonId);
        List<RoutineExecution> executions = rounds.isEmpty()
                ? List.of()
                : repository.findRoutineExecutionsBySeasonRoundIds(
                        rounds.stream().map(SeasonRound::getId).toList()
                );
        List<Decision> decisions = repository.findDecisionsBySeasonId(seasonId);
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        List<HandoffItem> handoffItems = roleIds.isEmpty()
                ? List.of()
                : repository.findHandoffItemsByRoleIds(roleIds);
        List<RoleResource> resources = roleIds.isEmpty()
                ? List.of()
                : repository.findRoleResourcesByRoleIds(roleIds);

        Map<UUID, Member> membersById = indexMembers(members);
        Map<UUID, List<RoutineExecution>> executionsByRoundId = executionsByRoundId(executions);
        return new WorkspaceResult(
                new TeamResult(scope.team().getId(), scope.team().getName()),
                toSeasonResult(scope.season()),
                members.stream().map(this::toMemberResult).toList(),
                roles.stream().map(this::toRoleResult).toList(),
                routines.stream().map(this::toRoutineResult).toList(),
                rounds.stream()
                        .map(round -> toSeasonRoundResult(
                                round,
                                executionsByRoundId.getOrDefault(round.getId(), List.of())
                        ))
                        .toList(),
                decisions.stream().map(decision -> toDecisionResult(decision, membersById)).toList(),
                handoffItems.stream().map(this::toHandoffItemResult).toList(),
                resources.stream().map(this::toRoleResourceResult).toList()
        );
    }

    @Override
    @Transactional
    public RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        Role role = Role.create(
                UUID.randomUUID(),
                teamId,
                command.name(),
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE,
                idempotencyKey,
                fingerprintRoleRequest(teamId, seasonId, role),
                role.getId()
        );
        if (attempt.replayResourceId() != null) {
            Role existing = repository.findRoleById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.ROLE));
            return toRoleResult(existing);
        }
        validateMemberOwnership(teamId, role.getCurrentMemberId());
        validateMemberOwnership(teamId, role.getNextMemberId());
        if (repository.existsRoleByTeamIdAndName(teamId, role.getName())) {
            throw new RoleNameConflictException();
        }
        reserveContentCreation(attempt.reservation());
        return toRoleResult(repository.saveRole(role));
    }

    @Override
    @Transactional
    public RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Role role = requireRole(teamId, roleId);
        String normalizedName = Role.normalizeName(command.name());
        validateMemberOwnership(teamId, command.currentMemberId());
        validateMemberOwnership(teamId, command.nextMemberId());
        if (repository.existsRoleByTeamIdAndNameAndIdNot(teamId, normalizedName, roleId)) {
            throw new RoleNameConflictException();
        }
        role.update(
                normalizedName,
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        return toRoleResult(repository.saveRole(role));
    }

    @Override
    @Transactional
    public RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoutineCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail()
        );
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.ROUTINE,
                idempotencyKey,
                fingerprintRoutineRequest(teamId, seasonId, routine),
                routine.getId()
        );
        if (attempt.replayResourceId() != null) {
            Routine existing = repository.findRoutineById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.ROUTINE));
            requireRole(teamId, existing.getOwnerRoleId());
            return toRoutineResult(existing);
        }
        requireRole(teamId, routine.getOwnerRoleId());
        reserveContentCreation(attempt.reservation());
        return toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            UpdateRoutineCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Routine routine = repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));
        requireRole(teamId, command.ownerRoleId());
        routine.update(
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail()
        );
        return toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateSeasonRoundCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                command.name(),
                command.meetingDate()
        );
        if (!scope.season().contains(round.getMeetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.ROUND,
                idempotencyKey,
                fingerprintSeasonRoundRequest(teamId, seasonId, round),
                round.getId()
        );
        if (attempt.replayResourceId() != null) {
            SeasonRound existing = repository.findSeasonRoundById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.ROUND));
            return toSeasonRoundResult(
                    existing,
                    repository.findRoutineExecutionsBySeasonRoundIds(List.of(existing.getId()))
            );
        }
        if (repository.existsSeasonRoundBySeasonIdAndName(seasonId, round.getName())) {
            throw new SeasonRoundNameConflictException();
        }

        List<RoutineExecution> executions = repository.findRoutinesBySeasonId(seasonId).stream()
                .map(routine -> RoutineExecution.snapshot(UUID.randomUUID(), round.getId(), routine))
                .toList();
        reserveContentCreation(attempt.reservation());
        SeasonRound savedRound = repository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return toSeasonRoundResult(savedRound, savedExecutions);
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRound(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            UpdateSeasonRoundCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        SeasonRound round = requireActiveSeasonRound(seasonId, roundId);
        String normalizedName = SeasonRound.normalizeName(command.name());
        if (!scope.season().contains(command.meetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        if (repository.existsSeasonRoundBySeasonIdAndNameAndIdNot(
                seasonId,
                normalizedName,
                round.getId()
        )) {
            throw new SeasonRoundNameConflictException();
        }
        round.update(normalizedName, command.meetingDate());
        SeasonRound saved = repository.saveSeasonRound(round);
        return toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIds(List.of(saved.getId()))
        );
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRoundArchive(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            String accessKey,
            boolean archived
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        return toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId())
        );
    }

    @Override
    @Transactional
    public RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            String accessKey,
            boolean completed
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireActiveSeasonRoundWithSharedLock(seasonId, roundId);
        RoutineExecution execution = repository.findRoutineExecutionById(executionId)
                .filter(found -> found.getSeasonRoundId().equals(roundId))
                .orElseThrow(() -> notFound(
                        "ROUTINE_EXECUTION_NOT_FOUND",
                        "루틴 실행 기록을 찾을 수 없습니다"
                ));
        execution.updateCompletion(completed);
        return toRoutineExecutionResult(repository.saveRoutineExecution(execution));
    }

    @Override
    @Transactional
    public DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        Decision decision = Decision.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.reason(),
                command.alternative(),
                Instant.now(clock),
                command.authorMemberId(),
                command.roleIds()
        );
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.DECISION,
                idempotencyKey,
                fingerprintDecisionRequest(teamId, seasonId, decision),
                decision.getId()
        );
        if (attempt.replayResourceId() != null) {
            Decision existing = repository.findDecisionById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.DECISION));
            Member existingAuthor = requireMember(teamId, existing.getAuthorMemberId());
            return toDecisionResult(existing, Map.of(existingAuthor.getId(), existingAuthor));
        }
        Member author = requireMember(teamId, decision.getAuthorMemberId());
        validateRoleOwnership(teamId, decision.getRoleIds());
        reserveContentCreation(attempt.reservation());
        Decision saved = repository.saveDecision(decision);
        return toDecisionResult(saved, Map.of(author.getId(), author));
    }

    @Override
    @Transactional
    public DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            UpdateDecisionCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Decision decision = requireActiveDecision(seasonId, decisionId);
        Member author = requireMember(teamId, command.authorMemberId());
        validateRoleOwnership(teamId, command.roleIds());
        decision.update(
                command.title(),
                command.reason(),
                command.alternative(),
                command.authorMemberId(),
                command.roleIds()
        );
        return toDecisionResult(
                repository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    @Override
    @Transactional
    public DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Decision decision = requireDecision(seasonId, decisionId);
        Member author = requireMember(teamId, decision.getAuthorMemberId());
        decision.updateArchive(archived, Instant.now(clock));
        return toDecisionResult(
                repository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    @Override
    @Transactional
    public HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                        command.roleId(),
                        command.label(),
                        command.category(),
                        false
        );
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.HANDOFF_ITEM,
                idempotencyKey,
                fingerprintHandoffItemRequest(teamId, seasonId, item),
                item.getId()
        );
        if (attempt.replayResourceId() != null) {
            HandoffItem existing = repository.findHandoffItemById(attempt.replayResourceId())
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.HANDOFF_ITEM));
            requireRole(teamId, existing.getRoleId());
            return toHandoffItemResult(existing);
        }
        requireRole(teamId, item.getRoleId());
        reserveContentCreation(attempt.reservation());
        return toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            UpdateHandoffItemCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        HandoffItem item = requireActiveHandoffItem(teamId, itemId);
        requireRole(teamId, command.roleId());
        item.update(command.roleId(), command.label(), command.category());
        return toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        HandoffItem item = requireActiveHandoffItem(teamId, itemId);
        item.updateCompletion(completed);
        return toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        HandoffItem item = requireHandoffItem(teamId, itemId);
        item.updateArchive(archived, Instant.now(clock));
        return toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                command.roleId(),
                command.title(),
                command.url(),
                command.description()
        );
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_RESOURCE,
                idempotencyKey,
                fingerprintRoleResourceRequest(teamId, seasonId, resource),
                resource.getId()
        );
        if (attempt.replayResourceId() != null) {
            RoleResource existing = repository.findRoleResourceById(attempt.replayResourceId())
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.ROLE_RESOURCE));
            requireRole(teamId, existing.getRoleId());
            return toRoleResourceResult(existing);
        }
        requireRole(teamId, resource.getRoleId());
        reserveContentCreation(attempt.reservation());
        return toRoleResourceResult(repository.saveRoleResource(resource));
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        RoleResource resource = repository.findRoleResourceById(resourceId)
                .orElseThrow(() -> notFound("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));
        repository.findRoleById(resource.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));
        requireRole(teamId, command.roleId());
        resource.update(command.roleId(), command.title(), command.url(), command.description());
        return toRoleResourceResult(repository.saveRoleResource(resource));
    }

    private AuthorizedScope authorize(UUID teamId, UUID seasonId, String accessKey) {
        AuthorizedScope scope = requireScope(teamId, seasonId);
        verifyAccessKey(scope.team(), accessKey);
        return scope;
    }

    private AuthorizedScope authorizeMutation(UUID teamId, UUID seasonId, String accessKey) {
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = requireSeason(teamId, seasonId);
        verifyAccessKey(team, accessKey);
        return new AuthorizedScope(team, season);
    }

    private AuthorizedScope requireScope(UUID teamId, UUID seasonId) {
        Team team = repository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        return new AuthorizedScope(team, requireSeason(teamId, seasonId));
    }

    private Season requireSeason(UUID teamId, UUID seasonId) {
        return repository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
    }

    private void verifyAccessKey(Team team, String accessKey) {
        if (accessKey == null || accessKey.isBlank()) {
            throw new WorkspaceAccessDeniedException();
        }
        if (!matchesAccessKey(team, accessKey)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    private boolean matchesAccessKey(Team team, String accessKey) {
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(team.getAccessKeyHash());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 접근 키 해시가 올바르지 않습니다", exception);
        }
        byte[] actual = sha256(accessKey);
        return MessageDigest.isEqual(expected, actual);
    }

    private AccessKeyResult replayAccessKeyChange(Team team, AccessKeyChange change) {
        if (!repository.existsAccessKeyChangeHistory(team.getId(), change.idempotencyHash())) {
            return null;
        }
        if (!change.idempotencyHash().equals(team.getLastAccessKeyChangeIdempotencyHash())) {
            throw new IdempotencyReplayExpiredException();
        }
        if (!matchesAccessKey(team, change.accessKey())) {
            throw new IllegalStateException("저장된 접근 키 변경 결과가 멱등 키와 일치하지 않습니다");
        }
        return new AccessKeyResult(change.accessKey());
    }

    private AccessKeyResult replaceAccessKey(Team team, AccessKeyChange change) {
        team.changeAccessKey(hashAccessKeyHex(change.accessKey()), change.idempotencyHash());
        repository.saveTeam(team);
        repository.saveAccessKeyChangeHistory(AccessKeyChangeHistory.create(
                UUID.randomUUID(),
                team.getId(),
                change.idempotencyHash()
        ));
        return new AccessKeyResult(change.accessKey());
    }

    private ContentCreationAttempt contentCreationAttempt(
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyKey,
            String requestFingerprint,
            UUID resourceId
    ) {
        String idempotencyHash = contentIdempotencyHash(
                teamId,
                seasonId,
                operation,
                idempotencyKey
        );
        ContentCreationIdempotency existing = repository.findContentCreationIdempotency(
                teamId,
                idempotencyHash
        ).orElse(null);
        if (existing != null) {
            validateContentCreationReplay(existing, teamId, seasonId, operation, requestFingerprint);
            return ContentCreationAttempt.replay(existing.getResourceId());
        }
        return ContentCreationAttempt.create(ContentCreationIdempotency.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                operation,
                idempotencyHash,
                requestFingerprint,
                resourceId
        ));
    }

    private void validateContentCreationReplay(
            ContentCreationIdempotency existing,
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String requestFingerprint
    ) {
        if (!existing.getTeamId().equals(teamId)
                || !existing.getSeasonId().equals(seasonId)
                || existing.getOperation() != operation) {
            throw new IllegalStateException("저장된 콘텐츠 생성 멱등 범위가 요청과 일치하지 않습니다");
        }
        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
    }

    private void reserveContentCreation(ContentCreationIdempotency reservation) {
        if (reservation == null) {
            throw new IllegalStateException("새 콘텐츠 생성 요청에 멱등 예약이 없습니다");
        }
        repository.saveContentCreationIdempotency(reservation);
    }

    private IllegalStateException missingIdempotentResource(ContentCreationOperation operation) {
        return new IllegalStateException("멱등 생성된 " + operation.name() + " 리소스를 찾을 수 없습니다");
    }

    private void requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new DomainValidationException(
                    "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
            );
        }
    }

    private void verifyWorkspaceCreationPermission(String creationKey) {
        if (workspaceCreationKey.isBlank()) {
            return;
        }
        if (!matchesConfiguredCreationKey(creationKey)) {
            throw new WorkspaceCreationDeniedException();
        }
    }

    private void verifyWorkspaceRecoveryPermission(String recoveryKey) {
        if (workspaceRecoveryKey.isBlank() || !matchesConfiguredSecret(workspaceRecoveryKey, recoveryKey)) {
            throw new WorkspaceRecoveryDeniedException();
        }
    }

    private boolean matchesConfiguredCreationKey(String presentedKey) {
        return matchesConfiguredSecret(workspaceCreationKey, presentedKey);
    }

    private boolean matchesConfiguredSecret(String configuredSecret, String presentedSecret) {
        if (presentedSecret == null) {
            return false;
        }
        byte[] expected = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presentedSecret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private List<Member> createMembers(UUID teamId, List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            throw new DomainValidationException("구성원은 한 명 이상이어야 합니다");
        }
        List<Member> members = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        for (String memberName : memberNames) {
            Member member = Member.create(UUID.randomUUID(), teamId, memberName);
            if (!normalizedNames.add(member.getName())) {
                throw new DomainValidationException("구성원 이름은 중복될 수 없습니다");
            }
            members.add(member);
        }
        return members;
    }

    private void validateMemberOwnership(UUID teamId, UUID memberId) {
        if (memberId != null) {
            requireMember(teamId, memberId);
        }
    }

    private Member requireMember(UUID teamId, UUID memberId) {
        return repository.findMemberById(memberId)
                .filter(member -> member.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다"));
    }

    private Role requireRole(UUID teamId, UUID roleId) {
        return repository.findRoleById(roleId)
                .filter(role -> role.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));
    }

    private SeasonRound requireSeasonRound(UUID seasonId, UUID roundId) {
        return repository.findSeasonRoundById(roundId)
                .filter(round -> round.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
    }

    private SeasonRound requireActiveSeasonRound(UUID seasonId, UUID roundId) {
        SeasonRound round = requireSeasonRound(seasonId, roundId);
        if (round.getArchivedAt() != null) {
            throw notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다");
        }
        return round;
    }

    private SeasonRound requireSeasonRoundForUpdate(UUID seasonId, UUID roundId) {
        return repository.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, roundId)
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
    }

    private SeasonRound requireActiveSeasonRoundWithSharedLock(UUID seasonId, UUID roundId) {
        SeasonRound round = repository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                        seasonId,
                        roundId
                )
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
        if (round.getArchivedAt() != null) {
            throw notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다");
        }
        return round;
    }

    private Decision requireDecision(UUID seasonId, UUID decisionId) {
        return repository.findDecisionById(decisionId)
                .filter(decision -> decision.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("DECISION_NOT_FOUND", "결정 기록을 찾을 수 없습니다"));
    }

    private Decision requireActiveDecision(UUID seasonId, UUID decisionId) {
        Decision decision = requireDecision(seasonId, decisionId);
        if (decision.getArchivedAt() != null) {
            throw notFound("DECISION_NOT_FOUND", "결정 기록을 찾을 수 없습니다");
        }
        return decision;
    }

    private HandoffItem requireHandoffItem(UUID teamId, UUID itemId) {
        HandoffItem item = repository.findHandoffItemById(itemId)
                .orElseThrow(() -> notFound(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ));
        repository.findRoleById(item.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ));
        return item;
    }

    private HandoffItem requireActiveHandoffItem(UUID teamId, UUID itemId) {
        HandoffItem item = requireHandoffItem(teamId, itemId);
        if (item.getArchivedAt() != null) {
            throw notFound("HANDOFF_ITEM_NOT_FOUND", "인수인계 항목을 찾을 수 없습니다");
        }
        return item;
    }

    private void validateRoleOwnership(UUID teamId, List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new DomainValidationException("관련 역할은 한 개 이상이어야 합니다");
        }
        Set<UUID> expected = new HashSet<>(roleIds);
        if (expected.size() != roleIds.size()) {
            throw new DomainValidationException("관련 역할은 중복될 수 없습니다");
        }
        Set<UUID> found = new HashSet<>(repository.findExistingRoleIds(teamId, roleIds));
        if (!found.equals(expected)) {
            throw notFound("ROLE_NOT_FOUND", "관련 역할을 찾을 수 없습니다");
        }
    }

    private Map<UUID, Member> indexMembers(List<Member> members) {
        Map<UUID, Member> result = new HashMap<>();
        for (Member member : members) {
            result.put(member.getId(), member);
        }
        return result;
    }

    private Map<UUID, List<RoutineExecution>> executionsByRoundId(List<RoutineExecution> executions) {
        Map<UUID, List<RoutineExecution>> result = new HashMap<>();
        for (RoutineExecution execution : executions) {
            result.computeIfAbsent(execution.getSeasonRoundId(), ignored -> new ArrayList<>())
                    .add(execution);
        }
        return result;
    }

    private SeasonResult toSeasonResult(Season season) {
        return new SeasonResult(season.getId(), season.getName(), season.getStartDate(), season.getEndDate());
    }

    private MemberResult toMemberResult(Member member) {
        int codePoint = member.getName().codePointAt(0);
        String initials = new String(Character.toChars(codePoint));
        String tone = MEMBER_TONES[Math.floorMod(member.getId().hashCode(), MEMBER_TONES.length)];
        return new MemberResult(member.getId(), member.getName(), initials, tone);
    }

    private RoleResult toRoleResult(Role role) {
        return new RoleResult(
                role.getId(),
                role.getName(),
                role.getPurpose(),
                role.getCurrentMemberId(),
                role.getNextMemberId(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate(),
                List.copyOf(role.getResponsibilities()),
                role.getRisk()
        );
    }

    private RoutineResult toRoutineResult(Routine routine) {
        return new RoutineResult(
                routine.getId(),
                routine.getTitle(),
                routine.getPhase(),
                routine.getDueLabel(),
                routine.getOwnerRoleId(),
                routine.getDetail()
        );
    }

    private SeasonRoundResult toSeasonRoundResult(
            SeasonRound round,
            List<RoutineExecution> executions
    ) {
        return new SeasonRoundResult(
                round.getId(),
                round.getName(),
                round.getMeetingDate(),
                executions.stream().map(this::toRoutineExecutionResult).toList(),
                round.getArchivedAt()
        );
    }

    private RoutineExecutionResult toRoutineExecutionResult(RoutineExecution execution) {
        return new RoutineExecutionResult(
                execution.getId(),
                execution.getSeasonRoundId(),
                execution.getRoutineId(),
                execution.getTitle(),
                execution.getPhase(),
                execution.getDueLabel(),
                execution.getOwnerRoleId(),
                execution.getStatus(),
                execution.getDetail()
        );
    }

    private DecisionResult toDecisionResult(Decision decision, Map<UUID, Member> membersById) {
        Member author = membersById.get(decision.getAuthorMemberId());
        if (author == null) {
            throw new IllegalStateException("결정 작성자 구성원을 찾을 수 없습니다");
        }
        return new DecisionResult(
                decision.getId(),
                decision.getTitle(),
                decision.getReason(),
                decision.getAlternative(),
                decision.getCreatedAt(),
                decision.getAuthorMemberId(),
                author.getName(),
                List.copyOf(decision.getRoleIds()),
                decision.getArchivedAt()
        );
    }

    private HandoffItemResult toHandoffItemResult(HandoffItem item) {
        return new HandoffItemResult(
                item.getId(),
                item.getRoleId(),
                item.getLabel(),
                item.getCategory(),
                item.isCompleted(),
                item.getArchivedAt()
        );
    }

    private RoleResourceResult toRoleResourceResult(RoleResource resource) {
        return new RoleResourceResult(
                resource.getId(),
                resource.getRoleId(),
                resource.getTitle(),
                resource.getUrl(),
                resource.getDescription()
        );
    }

    private String deriveInitialAccessKey(String idempotencyKey) {
        byte[] derived = hashDomainValue(ACCESS_KEY_DERIVATION_DOMAIN, idempotencyKey);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    private AccessKeyChange deriveAccessKeyChange(
            String idempotencyHashDomain,
            String accessKeyDomain,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) {
        List<String> values = List.of(teamId.toString(), seasonId.toString(), idempotencyKey);
        String idempotencyHash = HexFormat.of().formatHex(hashDomainValues(idempotencyHashDomain, values));
        String accessKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hashDomainValues(accessKeyDomain, values));
        return new AccessKeyChange(idempotencyHash, accessKey);
    }

    private String contentIdempotencyHash(
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyKey
    ) {
        return HexFormat.of().formatHex(hashDomainValues(
                CONTENT_IDEMPOTENCY_HASH_DOMAIN + ":" + operation.name(),
                List.of(teamId.toString(), seasonId.toString(), idempotencyKey)
        ));
    }

    private String fingerprintRoleRequest(UUID teamId, UUID seasonId, Role role) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROLE, teamId, seasonId);
        updateDigest(digest, role.getName());
        updateDigest(digest, role.getPurpose());
        updateNullableDigest(digest, role.getCurrentMemberId());
        updateNullableDigest(digest, role.getNextMemberId());
        updateNullableDigest(digest, role.getAssignmentStartDate());
        updateNullableDigest(digest, role.getAssignmentEndDate());
        updateDigest(digest, Integer.toString(role.getResponsibilities().size()));
        for (String responsibility : role.getResponsibilities()) {
            updateDigest(digest, responsibility);
        }
        updateNullableDigest(digest, role.getRisk());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintRoutineRequest(UUID teamId, UUID seasonId, Routine routine) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROUTINE, teamId, seasonId);
        updateDigest(digest, routine.getTitle());
        updateDigest(digest, routine.getPhase().name());
        updateDigest(digest, routine.getDueLabel());
        updateDigest(digest, routine.getOwnerRoleId().toString());
        updateDigest(digest, routine.getDetail());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintSeasonRoundRequest(UUID teamId, UUID seasonId, SeasonRound round) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROUND, teamId, seasonId);
        updateDigest(digest, round.getName());
        updateDigest(digest, round.getMeetingDate().toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintDecisionRequest(UUID teamId, UUID seasonId, Decision decision) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.DECISION, teamId, seasonId);
        updateDigest(digest, decision.getTitle());
        updateDigest(digest, decision.getReason());
        updateDigest(digest, decision.getAlternative());
        updateDigest(digest, decision.getAuthorMemberId().toString());
        updateDigest(digest, Integer.toString(decision.getRoleIds().size()));
        for (UUID roleId : decision.getRoleIds()) {
            updateDigest(digest, roleId.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintHandoffItemRequest(
            UUID teamId,
            UUID seasonId,
            HandoffItem item
    ) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.HANDOFF_ITEM, teamId, seasonId);
        updateDigest(digest, item.getRoleId().toString());
        updateDigest(digest, item.getLabel());
        updateDigest(digest, item.getCategory().name());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintRoleResourceRequest(
            UUID teamId,
            UUID seasonId,
            RoleResource resource
    ) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROLE_RESOURCE, teamId, seasonId);
        updateDigest(digest, resource.getRoleId().toString());
        updateDigest(digest, resource.getTitle());
        updateDigest(digest, resource.getUrl());
        updateNullableDigest(digest, resource.getDescription());
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest contentRequestDigest(
            ContentCreationOperation operation,
            UUID teamId,
            UUID seasonId
    ) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, CONTENT_REQUEST_FINGERPRINT_DOMAIN + ":" + operation.name());
        updateDigest(digest, teamId.toString());
        updateDigest(digest, seasonId.toString());
        return digest;
    }

    private void updateNullableDigest(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        updateDigest(digest, value.toString());
    }

    private String fingerprintCreationRequest(Team team, Season season, List<Member> members) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, REQUEST_FINGERPRINT_DOMAIN);
        updateDigest(digest, team.getName());
        updateDigest(digest, season.getName());
        updateDigest(digest, season.getStartDate().toString());
        updateDigest(digest, season.getEndDate().toString());
        List<String> normalizedMemberNames = members.stream()
                .map(Member::getName)
                .sorted()
                .toList();
        updateDigest(digest, Integer.toString(normalizedMemberNames.size()));
        for (String memberName : normalizedMemberNames) {
            updateDigest(digest, memberName);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String hashDomainValueHex(String domain, String value) {
        return HexFormat.of().formatHex(hashDomainValue(domain, value));
    }

    private byte[] hashDomainValue(String domain, String value) {
        return hashDomainValues(domain, List.of(value));
    }

    private byte[] hashDomainValues(String domain, List<String> values) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, domain);
        for (String value : values) {
            updateDigest(digest, value);
        }
        return digest.digest();
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String hashAccessKeyHex(String accessKey) {
        return HexFormat.of().formatHex(sha256(accessKey));
    }

    private byte[] sha256(String value) {
        return newSha256Digest().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }

    private record AuthorizedScope(Team team, Season season) {
    }

    private record AccessKeyChange(String idempotencyHash, String accessKey) {
    }

    private record ContentCreationAttempt(
            ContentCreationIdempotency reservation,
            UUID replayResourceId
    ) {

        private static ContentCreationAttempt create(ContentCreationIdempotency reservation) {
            return new ContentCreationAttempt(reservation, null);
        }

        private static ContentCreationAttempt replay(UUID resourceId) {
            return new ContentCreationAttempt(null, resourceId);
        }
    }
}
