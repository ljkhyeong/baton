package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
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
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int MAX_SUCCESSOR_COPY_COUNT = 100;

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
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacyAccessKeyChange(
                scope.team(),
                ROTATE_IDEMPOTENCY_HASH_DOMAIN,
                ROTATE_ACCESS_KEY_DERIVATION_DOMAIN,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
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
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacyAccessKeyChange(
                scope.team(),
                RECOVER_IDEMPOTENCY_HASH_DOMAIN,
                RECOVER_ACCESS_KEY_DERIVATION_DOMAIN,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
        }
        return replaceAccessKey(scope.team(), change);
    }

    @Override
    public WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey) {
        AuthorizedScope scope = authorize(teamId, seasonId, accessKey);
        List<Member> members = repository.findMembersByTeamId(teamId);
        List<Season> seasons = repository.findSeasonsByTeamId(teamId);
        List<Role> roles = repository.findRolesByTeamIdAndSeasonId(teamId, seasonId);
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
        List<RoleHandoff> roleHandoffs = roleIds.isEmpty()
                ? List.of()
                : repository.findRoleHandoffsByRoleIds(roleIds);

        Map<UUID, Member> membersById = indexMembers(members);
        Map<UUID, List<RoutineExecution>> executionsByRoundId = executionsByRoundId(executions);
        return new WorkspaceResult(
                new TeamResult(scope.team().getId(), scope.team().getName()),
                toSeasonResult(scope.season()),
                seasons.stream().map(this::toSeasonSummaryResult).toList(),
                members.stream().map(this::toMemberResult).toList(),
                roles.stream().map(this::toRoleResult).toList(),
                routines.stream().map(this::toRoutineResult).toList(),
                rounds.stream()
                        .map(round -> toSeasonRoundResult(
                                round,
                                executionsByRoundId.getOrDefault(round.getId(), List.of()),
                                scope.season()
                        ))
                        .toList(),
                decisions.stream().map(decision -> toDecisionResult(decision, membersById)).toList(),
                handoffItems.stream().map(this::toHandoffItemResult).toList(),
                resources.stream().map(this::toRoleResourceResult).toList(),
                roleHandoffs.stream().map(this::toRoleHandoffResult).toList()
        );
    }

    @Override
    @Transactional
    public SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateSeasonCommand command
    ) {
        AuthorizedScope scope = authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        String normalizedName = Season.normalizeName(command.name());
        validateSeasonRangeAgainstExistingContent(
                teamId,
                seasonId,
                command.startDate(),
                command.endDate()
        );
        if (repository.existsSeasonByTeamIdAndNameAndIdNot(teamId, normalizedName, seasonId)) {
            throw new SeasonNameConflictException();
        }
        scope.season().update(normalizedName, command.startDate(), command.endDate());
        return toSeasonResult(repository.saveSeason(scope.season()));
    }

    @Override
    @Transactional
    public SeasonResult updateRoundSchedule(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            UpdateRoundScheduleCommand command
    ) {
        AuthorizedScope scope = authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        Season season = scope.season();
        List<SeasonRound> rounds = repository.findSeasonRoundsBySeasonId(seasonId);
        String normalizedTimeZone = Season.normalizeTimeZone(command.timeZone());
        if (!Objects.equals(season.getTimeZone(), normalizedTimeZone) && !rounds.isEmpty()) {
            throw new DomainValidationException("회차가 생성된 뒤에는 시즌 시간대를 변경할 수 없습니다");
        }
        if (command.enabled()) {
            requireDeadlineRules(repository.findRoutinesBySeasonId(seasonId));
        }

        season.configureRoundSchedule(
                command.firstMeetingDate(),
                command.meetingTime(),
                command.recurrence(),
                command.generationLeadDays(),
                command.enabled()
        );
        season.updateTimeZone(normalizedTimeZone);
        return toSeasonResult(repository.saveSeason(season));
    }

    @Override
    @Transactional
    public SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            boolean ended
    ) {
        AuthorizedScope scope = authorizeSeasonLifecycle(teamId, seasonId, accessKey);
        if (ended && repository.existsOpenRoleHandoffBySeasonId(seasonId)) {
            throw handoffStateConflict(
                    "준비 중이거나 수락을 기다리는 바통을 수락 또는 취소한 뒤 시즌을 종료해 주세요"
            );
        }
        if (!ended) {
            if (repository.existsSeasonByPreviousSeasonId(seasonId)) {
                throw new SeasonSuccessorExistsException();
            }
            repository.findActiveSeasonByTeamId(teamId)
                    .filter(active -> !active.getId().equals(seasonId))
                    .ifPresent(active -> {
                        throw new WorkspaceContentConflictException();
                    });
        }
        scope.season().updateEnding(ended, Instant.now(clock));
        return toSeasonResult(repository.saveSeason(scope.season()));
    }

    @Override
    @Transactional
    public NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            String accessKey,
            CreateNextSeasonCommand command
    ) {
        requireValidIdempotencyKey(idempotencyKey);
        AuthorizedScope scope = authorizeSeasonLifecycle(teamId, sourceSeasonId, accessKey);
        Season sourceSeason = scope.season();
        if (repository.existsOpenRoleHandoffBySeasonId(sourceSeasonId)) {
            throw handoffStateConflict(
                    "준비 중이거나 수락을 기다리는 바통을 수락 또는 취소한 뒤 다음 시즌을 시작해 주세요"
            );
        }
        List<UUID> roleIds = normalizedCopyIds(command.roleIds(), "복사할 역할");
        List<UUID> routineIds = normalizedCopyIds(command.routineIds(), "복사할 루틴");
        UUID targetSeasonId = UUID.randomUUID();
        Season targetSeason = Season.createSuccessor(
                targetSeasonId,
                teamId,
                sourceSeasonId,
                command.name(),
                command.startDate(),
                command.endDate(),
                sourceSeason.getTimeZone()
        );
        if (!targetSeason.getStartDate().isAfter(sourceSeason.getEndDate())) {
            throw new DomainValidationException("다음 시즌 시작일은 이전 시즌 종료일보다 늦어야 합니다");
        }

        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                sourceSeasonId,
                ContentCreationOperation.SEASON,
                idempotencyKey,
                fingerprintNextSeasonRequest(teamId, sourceSeasonId, targetSeason, roleIds, routineIds),
                targetSeasonId
        );
        if (attempt.replayResourceId() != null) {
            Season existing = repository.findSeasonById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> sourceSeasonId.equals(found.getPreviousSeasonId()))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.SEASON));
            return toNextSeasonResult(sourceSeason, existing);
        }

        if (repository.existsSeasonByPreviousSeasonId(sourceSeasonId)) {
            throw new SeasonSuccessorExistsException();
        }
        if (repository.existsSeasonByTeamIdAndName(teamId, targetSeason.getName())) {
            throw new SeasonNameConflictException();
        }
        repository.findActiveSeasonByTeamId(teamId)
                .filter(active -> !active.getId().equals(sourceSeasonId))
                .ifPresent(active -> {
                    throw new DomainValidationException("다른 활성 시즌이 있어 다음 시즌을 시작할 수 없습니다");
                });

        List<Role> sourceRoles = selectSourceRoles(teamId, sourceSeasonId, roleIds);
        List<Routine> sourceRoutines = selectSourceRoutines(sourceSeasonId, routineIds);
        Set<UUID> selectedRoleIds = new HashSet<>(roleIds);
        for (Routine routine : sourceRoutines) {
            if (!selectedRoleIds.contains(routine.getOwnerRoleId())) {
                throw new DomainValidationException("복사할 루틴의 담당 역할도 함께 선택해야 합니다");
            }
        }

        sourceSeason.updateEnding(true, Instant.now(clock));
        Season savedSourceSeason = repository.saveSeason(sourceSeason);
        reserveContentCreation(attempt.reservation());
        Season savedTargetSeason = repository.saveSeason(targetSeason);

        Map<UUID, UUID> copiedRoleIds = new HashMap<>();
        for (Role sourceRole : sourceRoles) {
            Role copiedRole = sourceRole.copyToSeason(UUID.randomUUID(), savedTargetSeason.getId());
            Role savedRole = repository.saveRole(copiedRole);
            copiedRoleIds.put(sourceRole.getId(), savedRole.getId());
        }
        for (Routine sourceRoutine : sourceRoutines) {
            UUID copiedOwnerRoleId = copiedRoleIds.get(sourceRoutine.getOwnerRoleId());
            if (copiedOwnerRoleId == null) {
                throw new IllegalStateException("복사된 루틴의 담당 역할 매핑을 찾을 수 없습니다");
            }
            repository.saveRoutine(sourceRoutine.copyToSeason(
                    UUID.randomUUID(),
                    savedTargetSeason.getId(),
                    copiedOwnerRoleId
            ));
        }
        return toNextSeasonResult(savedSourceSeason, savedTargetSeason);
    }

    @Override
    @Transactional
    public MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateMemberCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        Member member = Member.create(UUID.randomUUID(), teamId, command.name());
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.MEMBER,
                idempotencyKey,
                fingerprintMemberRequest(teamId, seasonId, member),
                member.getId()
        );
        if (attempt.replayResourceId() != null) {
            Member existing = repository.findMemberById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.MEMBER));
            return toMemberResult(existing);
        }
        if (repository.existsMemberByTeamIdAndName(teamId, member.getName())) {
            throw new MemberNameConflictException();
        }
        reserveContentCreation(attempt.reservation());
        return toMemberResult(repository.saveMember(member));
    }

    @Override
    @Transactional
    public MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            UpdateMemberCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Member member = requireMember(teamId, memberId);
        String normalizedName = Member.normalizeName(command.name());
        if (repository.existsMemberByTeamIdAndNameAndIdNot(teamId, normalizedName, memberId)) {
            throw new MemberNameConflictException();
        }
        member.rename(normalizedName);
        return toMemberResult(repository.saveMember(member));
    }

    @Override
    @Transactional
    public MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Member member = requireMember(teamId, memberId);
        member.updateDeactivation(deactivated, Instant.now(clock));
        return toMemberResult(repository.saveMember(member));
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
                seasonId,
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
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() -> missingIdempotentResource(ContentCreationOperation.ROLE));
            return toRoleResult(existing);
        }
        requireActiveMembersForNewReferences(
                teamId,
                role.getCurrentMemberId(),
                role.getNextMemberId()
        );
        validateRoleAssignmentDates(
                requireSeason(teamId, seasonId),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate()
        );
        if (repository.existsRoleBySeasonIdAndName(seasonId, role.getName())) {
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
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId).ifPresent(handoff -> {
            if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
                throw handoffStateConflict(
                        "전달된 바통의 역할 내용은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
            if (!Objects.equals(role.getCurrentMemberId(), command.currentMemberId())
                    || !Objects.equals(role.getNextMemberId(), command.nextMemberId())
                    || !Objects.equals(role.getAssignmentStartDate(), command.assignmentStartDate())
                    || !Objects.equals(role.getAssignmentEndDate(), command.assignmentEndDate())) {
                throw handoffStateConflict(
                        "진행 중인 바통의 담당자와 담당 기간은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
        });
        String normalizedName = Role.normalizeName(command.name());
        requireActiveMembersForNewReferences(
                teamId,
                Objects.equals(role.getCurrentMemberId(), command.currentMemberId())
                        ? null
                        : command.currentMemberId(),
                Objects.equals(role.getNextMemberId(), command.nextMemberId())
                        ? null
                        : command.nextMemberId()
        );
        validateRoleAssignmentDates(
                requireSeason(teamId, seasonId),
                command.assignmentStartDate(),
                command.assignmentEndDate()
        );
        if (repository.existsRoleBySeasonIdAndNameAndIdNot(seasonId, normalizedName, roleId)) {
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
    public RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            PrepareRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        UUID handoffId = UUID.randomUUID();
        ContentCreationAttempt attempt = contentCreationAttempt(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_HANDOFF,
                idempotencyKey,
                fingerprintRoleHandoffRequest(teamId, seasonId, roleId, command),
                handoffId
        );
        if (attempt.replayResourceId() != null) {
            RoleHandoff existing = repository.findRoleHandoffById(attempt.replayResourceId())
                    .filter(handoff -> handoff.getTeamId().equals(teamId))
                    .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                    .filter(handoff -> handoff.getRoleId().equals(roleId))
                    .orElseThrow(() -> missingIdempotentResource(
                            ContentCreationOperation.ROLE_HANDOFF
                    ));
            return toRoleHandoffTransitionResult(role, existing);
        }
        if (repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId).isPresent()) {
            throw handoffStateConflict("이 역할에는 이미 진행 중인 바통이 있습니다");
        }
        UUID fromMemberId = role.getCurrentMemberId();
        if (fromMemberId == null) {
            throw handoffStateConflict("현재 담당자를 지정한 뒤 바통 준비를 시작해 주세요");
        }
        if (role.getAssignmentStartDate() == null) {
            throw handoffStateConflict("현재 담당 시작일을 지정한 뒤 바통 준비를 시작해 주세요");
        }
        if (Objects.equals(fromMemberId, command.toMemberId())) {
            throw handoffStateConflict("현재 담당자와 다음 담당자는 달라야 합니다");
        }
        if (role.getNextMemberId() != null
                && !Objects.equals(role.getNextMemberId(), command.toMemberId())) {
            throw handoffStateConflict(
                    "역할에 지정된 다음 담당자와 바통 대상이 다릅니다"
            );
        }
        requireActiveMembersForNewReferences(teamId, fromMemberId, command.toMemberId());
        validateRoleAssignmentDates(
                scope.season(),
                command.incomingAssignmentStartDate(),
                command.incomingAssignmentEndDate()
        );

        Instant preparedAt = Instant.now(clock);
        role.prepareHandoff(command.toMemberId());
        RoleHandoff handoff = RoleHandoff.prepare(
                handoffId,
                teamId,
                seasonId,
                roleId,
                fromMemberId,
                command.toMemberId(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate(),
                command.incomingAssignmentStartDate(),
                command.incomingAssignmentEndDate(),
                preparedAt
        );
        reserveContentCreation(attempt.reservation());
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            TransferRoleHandoffCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return toRoleHandoffTransitionResult(role, handoff);
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return toRoleHandoffTransitionResult(role, handoff);
        }
        requireHandoffStatus(handoff, RoleHandoffStatus.PREPARING, "준비 중인 바통만 전달할 수 있습니다");
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 전달을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        requireActiveMembersForNewReferences(
                teamId,
                handoff.getFromMemberId(),
                handoff.getToMemberId()
        );

        List<HandoffItem> activeItems = repository.findHandoffItemsByRoleIds(List.of(roleId))
                .stream()
                .filter(item -> item.getArchivedAt() == null)
                .toList();
        int incompleteItemCount = (int) activeItems.stream()
                .filter(item -> !item.isCompleted())
                .count();
        int resourceCount = repository.findRoleResourcesByRoleIds(List.of(roleId)).size();
        boolean hasWarning = activeItems.isEmpty()
                || incompleteItemCount > 0
                || resourceCount == 0;
        if (hasWarning && !command.warningAcknowledged()) {
            throw new RoleHandoffWarningConfirmationRequiredException();
        }
        handoff.transfer(
                command.confirmedByMemberId(),
                Instant.now(clock),
                activeItems.size(),
                incompleteItemCount,
                resourceCount,
                command.warningAcknowledged()
        );
        return toRoleHandoffTransitionResult(
                role,
                repository.saveRoleHandoff(handoff)
        );
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED
                && Objects.equals(
                handoff.getAcceptedByMemberId(),
                command.confirmedByMemberId()
        )) {
            return toRoleHandoffTransitionResult(role, handoff);
        }
        requireHandoffStatus(
                handoff,
                RoleHandoffStatus.TRANSFERRED,
                "전달된 바통만 수락할 수 있습니다"
        );
        requireDeclaredConfirmer(
                handoff.getToMemberId(),
                command.confirmedByMemberId(),
                "다음 담당자 명의로 수락을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        requireActiveMembersForNewReferences(teamId, handoff.getToMemberId());
        validateRoleAssignmentDates(
                scope.season(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate()
        );

        Instant acceptedAt = Instant.now(clock);
        handoff.accept(command.confirmedByMemberId(), acceptedAt);
        role.acceptHandoff(
                handoff.getFromMemberId(),
                handoff.getToMemberId(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate()
        );
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        authorizeMutation(teamId, seasonId, accessKey);
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.CANCELLED
                && Objects.equals(
                handoff.getFromMemberId(),
                command.confirmedByMemberId()
        )) {
            return toRoleHandoffTransitionResult(role, handoff);
        }
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED) {
            throw handoffStateConflict("수락이 끝난 바통은 취소할 수 없습니다");
        }
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 바통 취소를 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);

        handoff.cancel(command.confirmedByMemberId(), Instant.now(clock));
        role.cancelHandoff(handoff.getToMemberId());
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return toRoleHandoffTransitionResult(savedRole, savedHandoff);
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
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        requireValidIdempotencyKey(idempotencyKey);
        requireDeadlineRuleForEnabledSchedule(
                scope.season(),
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail(),
                command.deadlineDayOffset(),
                command.deadlineTime()
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
            requireRole(teamId, seasonId, existing.getOwnerRoleId());
            return toRoutineResult(existing);
        }
        requireRole(teamId, seasonId, routine.getOwnerRoleId());
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
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        Routine routine = repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));
        requireRole(teamId, seasonId, command.ownerRoleId());
        requireDeadlineRuleForEnabledSchedule(
                scope.season(),
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        routine.update(
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail(),
                command.deadlineDayOffset(),
                command.deadlineTime()
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
                    repository.findRoutineExecutionsBySeasonRoundIds(List.of(existing.getId())),
                    scope.season()
            );
        }
        if (repository.existsSeasonRoundBySeasonIdAndName(seasonId, round.getName())) {
            throw new SeasonRoundNameConflictException();
        }

        List<RoutineExecution> executions = repository.findRoutinesBySeasonId(seasonId).stream()
                .map(routine -> RoutineExecution.snapshot(
                        UUID.randomUUID(),
                        round.getId(),
                        routine,
                        round.getMeetingDate(),
                        scope.season().getZoneId()
                ))
                .toList();
        reserveContentCreation(attempt.reservation());
        SeasonRound savedRound = repository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return toSeasonRoundResult(savedRound, savedExecutions, scope.season());
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
        SeasonRound round = requireActiveSeasonRoundForUpdate(seasonId, roundId);
        if (round.getOrigin() == RoundOrigin.AUTOMATIC) {
            throw new DomainValidationException("자동 생성된 회차의 날짜와 이름은 수정할 수 없습니다");
        }
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
        List<RoutineExecution> executions = repository.findRoutineExecutionsBySeasonRoundIds(
                List.of(saved.getId())
        );
        for (RoutineExecution execution : executions) {
            execution.reschedule(command.meetingDate(), scope.season().getZoneId());
        }
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return toSeasonRoundResult(
                saved,
                savedExecutions,
                scope.season()
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
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        return toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId()),
                scope.season()
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
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, accessKey);
        requireActiveSeasonRoundWithSharedLock(seasonId, roundId);
        RoutineExecution execution = repository.findRoutineExecutionById(executionId)
                .filter(found -> found.getSeasonRoundId().equals(roundId))
                .orElseThrow(() -> notFound(
                        "ROUTINE_EXECUTION_NOT_FOUND",
                        "루틴 실행 기록을 찾을 수 없습니다"
                ));
        execution.updateCompletion(completed);
        return toRoutineExecutionResult(
                repository.saveRoutineExecution(execution),
                scope.season().getZoneId()
        );
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
        Member author = requireActiveMembersForNewReferences(
                teamId,
                decision.getAuthorMemberId()
        ).get(decision.getAuthorMemberId());
        validateRoleOwnership(teamId, seasonId, decision.getRoleIds());
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
        Member author = Objects.equals(decision.getAuthorMemberId(), command.authorMemberId())
                ? requireMember(teamId, command.authorMemberId())
                : requireActiveMembersForNewReferences(teamId, command.authorMemberId())
                        .get(command.authorMemberId());
        validateRoleOwnership(teamId, seasonId, command.roleIds());
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
            requireRole(teamId, seasonId, existing.getRoleId());
            return toHandoffItemResult(existing);
        }
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
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
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(
                teamId,
                seasonId,
                item.getRoleId(),
                command.roleId()
        );
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
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
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
        HandoffItem item = requireHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
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
            requireRole(teamId, seasonId, existing.getRoleId());
            return toRoleResourceResult(existing);
        }
        requireEditableHandoffRoles(teamId, seasonId, resource.getRoleId());
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
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));
        requireEditableHandoffRoles(
                teamId,
                seasonId,
                resource.getRoleId(),
                command.roleId()
        );
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
        Season season = repository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        verifyAccessKey(team, accessKey);
        requireOpenSeason(season);
        return new AuthorizedScope(team, season);
    }

    private AuthorizedScope authorizeSeasonForUpdate(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        verifyAccessKey(team, accessKey);
        requireOpenSeason(season);
        return new AuthorizedScope(team, season);
    }

    private AuthorizedScope authorizeSeasonLifecycle(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        Team team = repository.findTeamByIdForUpdate(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
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

    private void requireOpenSeason(Season season) {
        if (season.isEnded()) {
            throw new SeasonEndedException();
        }
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

    private AccessKeyResult replayLegacyAccessKeyChange(
            Team team,
            String idempotencyHashDomain,
            String accessKeyDomain,
            String idempotencyKey
    ) {
        boolean foundExpiredReplay = false;
        for (Season season : repository.findSeasonsByTeamId(team.getId())) {
            AccessKeyChange legacyChange = deriveLegacyAccessKeyChange(
                    idempotencyHashDomain,
                    accessKeyDomain,
                    team.getId(),
                    season.getId(),
                    idempotencyKey
            );
            if (!repository.existsAccessKeyChangeHistory(
                    team.getId(),
                    legacyChange.idempotencyHash()
            )) {
                continue;
            }
            if (legacyChange.idempotencyHash().equals(
                    team.getLastAccessKeyChangeIdempotencyHash()
            )) {
                if (!matchesAccessKey(team, legacyChange.accessKey())) {
                    throw new IllegalStateException(
                            "저장된 접근 키 변경 결과가 기존 멱등 키와 일치하지 않습니다"
                    );
                }
                return new AccessKeyResult(legacyChange.accessKey());
            }
            foundExpiredReplay = true;
        }
        if (foundExpiredReplay) {
            throw new IdempotencyReplayExpiredException();
        }
        return null;
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

    private Map<UUID, Member> requireActiveMembersForNewReferences(
            UUID teamId,
            UUID... candidateMemberIds
    ) {
        List<UUID> memberIds = new ArrayList<>();
        for (UUID memberId : candidateMemberIds) {
            if (memberId != null && !memberIds.contains(memberId)) {
                memberIds.add(memberId);
            }
        }
        memberIds.sort(UUID::compareTo);
        if (memberIds.isEmpty()) {
            return Map.of();
        }

        List<Member> members = repository.findMembersByTeamIdAndIdsWithSharedLock(teamId, memberIds);
        Map<UUID, Member> membersById = indexMembers(members);
        for (UUID memberId : memberIds) {
            Member member = membersById.get(memberId);
            if (member == null) {
                throw notFound("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다");
            }
            if (!member.isActive()) {
                throw new DomainValidationException(
                        "비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다"
                );
            }
        }
        return membersById;
    }

    private Member requireMember(UUID teamId, UUID memberId) {
        return repository.findMemberById(memberId)
                .filter(member -> member.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다"));
    }

    private Role requireRole(UUID teamId, UUID seasonId, UUID roleId) {
        return repository.findRoleById(roleId)
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));
    }

    private Role requireRoleForUpdate(UUID teamId, UUID seasonId, UUID roleId) {
        return repository.findRoleByTeamIdAndSeasonIdAndIdForUpdate(teamId, seasonId, roleId)
                .orElseThrow(() -> notFound("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));
    }

    private RoleHandoff requireRoleHandoffForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId
    ) {
        return repository.findRoleHandoffByIdForUpdate(handoffId)
                .filter(handoff -> handoff.getTeamId().equals(teamId))
                .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                .filter(handoff -> handoff.getRoleId().equals(roleId))
                .orElseThrow(() -> notFound(
                        "ROLE_HANDOFF_NOT_FOUND",
                        "역할 바통을 찾을 수 없습니다"
                ));
    }

    private void requireEditableHandoffRoles(
            UUID teamId,
            UUID seasonId,
            UUID... candidateRoleIds
    ) {
        List<UUID> roleIds = new ArrayList<>();
        for (UUID roleId : candidateRoleIds) {
            if (roleId != null && !roleIds.contains(roleId)) {
                roleIds.add(roleId);
            }
        }
        roleIds.sort(UUID::compareTo);
        List<Role> roles = repository.findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
                teamId,
                seasonId,
                roleIds
        );
        if (roles.size() != roleIds.size()) {
            throw notFound("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다");
        }
        for (UUID roleId : roleIds) {
            repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId)
                    .filter(handoff -> handoff.getStatus() == RoleHandoffStatus.TRANSFERRED)
                    .ifPresent(handoff -> {
                        throw handoffStateConflict(
                                "전달된 바통의 항목과 자료는 수락 또는 취소 전까지 바꿀 수 없습니다"
                        );
                    });
        }
    }

    private void requireHandoffStatus(
            RoleHandoff handoff,
            RoleHandoffStatus expectedStatus,
            String message
    ) {
        if (handoff.getStatus() != expectedStatus) {
            throw handoffStateConflict(message);
        }
    }

    private void requireDeclaredConfirmer(
            UUID expectedMemberId,
            UUID confirmedByMemberId,
            String message
    ) {
        if (!Objects.equals(expectedMemberId, confirmedByMemberId)) {
            throw handoffStateConflict(message);
        }
    }

    private void requireRoleMatchesHandoff(Role role, RoleHandoff handoff) {
        if (!Objects.equals(role.getCurrentMemberId(), handoff.getFromMemberId())
                || !Objects.equals(role.getNextMemberId(), handoff.getToMemberId())) {
            throw handoffStateConflict(
                    "역할 담당자가 바통 준비 시점과 달라 최신 내용을 확인해 주세요"
            );
        }
    }

    private SeasonRound requireSeasonRoundForUpdate(UUID seasonId, UUID roundId) {
        return repository.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, roundId)
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
    }

    private SeasonRound requireActiveSeasonRoundForUpdate(UUID seasonId, UUID roundId) {
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        if (round.getArchivedAt() != null) {
            throw notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다");
        }
        return round;
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

    private HandoffItem requireHandoffItem(UUID teamId, UUID seasonId, UUID itemId) {
        HandoffItem item = repository.findHandoffItemById(itemId)
                .orElseThrow(() -> notFound(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ));
        repository.findRoleById(item.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ));
        return item;
    }

    private HandoffItem requireActiveHandoffItem(UUID teamId, UUID seasonId, UUID itemId) {
        HandoffItem item = requireHandoffItem(teamId, seasonId, itemId);
        if (item.getArchivedAt() != null) {
            throw notFound("HANDOFF_ITEM_NOT_FOUND", "인수인계 항목을 찾을 수 없습니다");
        }
        return item;
    }

    private void validateRoleOwnership(UUID teamId, UUID seasonId, List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new DomainValidationException("관련 역할은 한 개 이상이어야 합니다");
        }
        Set<UUID> expected = new HashSet<>(roleIds);
        if (expected.size() != roleIds.size()) {
            throw new DomainValidationException("관련 역할은 중복될 수 없습니다");
        }
        Set<UUID> found = new HashSet<>(
                repository.findExistingRoleIds(teamId, seasonId, roleIds)
        );
        if (!found.equals(expected)) {
            throw notFound("ROLE_NOT_FOUND", "관련 역할을 찾을 수 없습니다");
        }
    }

    private void validateRoleAssignmentDates(
            Season season,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate
    ) {
        if (assignmentStartDate != null && !season.contains(assignmentStartDate)) {
            throw new DomainValidationException("역할 배정 시작일은 시즌 기간 안에 있어야 합니다");
        }
        if (assignmentEndDate != null && !season.contains(assignmentEndDate)) {
            throw new DomainValidationException("역할 배정 종료일은 시즌 기간 안에 있어야 합니다");
        }
    }

    private void validateSeasonRangeAgainstExistingContent(
            UUID teamId,
            UUID seasonId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate validatedStartDate = Objects.requireNonNull(startDate, "시즌 시작일은 필수입니다");
        LocalDate validatedEndDate = Objects.requireNonNull(endDate, "시즌 종료일은 필수입니다");
        if (validatedStartDate.isAfter(validatedEndDate)) {
            throw new DomainValidationException("시즌 시작일은 종료일보다 늦을 수 없습니다");
        }
        for (SeasonRound round : repository.findSeasonRoundsBySeasonId(seasonId)) {
            LocalDate meetingDate = round.getMeetingDate();
            if (meetingDate != null
                    && (meetingDate.isBefore(validatedStartDate)
                    || meetingDate.isAfter(validatedEndDate))) {
                throw new DomainValidationException("기존 회차 날짜를 제외하도록 시즌 기간을 줄일 수 없습니다");
            }
        }
        List<Role> roles = repository.findRolesByTeamIdAndSeasonId(teamId, seasonId);
        for (Role role : roles) {
            LocalDate assignmentStartDate = role.getAssignmentStartDate();
            LocalDate assignmentEndDate = role.getAssignmentEndDate();
            if ((assignmentStartDate != null
                    && (assignmentStartDate.isBefore(validatedStartDate)
                    || assignmentStartDate.isAfter(validatedEndDate)))
                    || (assignmentEndDate != null
                    && (assignmentEndDate.isBefore(validatedStartDate)
                    || assignmentEndDate.isAfter(validatedEndDate)))) {
                throw new DomainValidationException("기존 역할 배정 기간을 제외하도록 시즌 기간을 줄일 수 없습니다");
            }
        }
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        if (!roleIds.isEmpty()) {
            for (RoleHandoff handoff : repository.findRoleHandoffsByRoleIds(roleIds)) {
                if (!handoff.isOpen()) {
                    continue;
                }
                LocalDate incomingStartDate = handoff.getIncomingAssignmentStartDate();
                LocalDate incomingEndDate = handoff.getIncomingAssignmentEndDate();
                if (incomingStartDate.isBefore(validatedStartDate)
                        || incomingStartDate.isAfter(validatedEndDate)
                        || (incomingEndDate != null
                        && (incomingEndDate.isBefore(validatedStartDate)
                        || incomingEndDate.isAfter(validatedEndDate)))) {
                    throw new DomainValidationException(
                            "준비 중인 바통의 다음 담당 기간을 제외하도록 시즌 기간을 줄일 수 없습니다"
                    );
                }
            }
        }
    }

    private void requireDeadlineRules(List<Routine> routines) {
        for (Routine routine : routines) {
            if (routine.getDeadlineDayOffset() == null || routine.getDeadlineTime() == null) {
                throw new DomainValidationException(
                        "자동 회차를 사용하려면 모든 루틴에 실제 마감 규칙이 필요합니다"
                );
            }
        }
    }

    private void requireDeadlineRuleForEnabledSchedule(
            Season season,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule != null
                && schedule.isEnabled()
                && (deadlineDayOffset == null || deadlineTime == null)) {
            throw new DomainValidationException(
                    "자동 회차를 사용하는 동안 루틴의 실제 마감 규칙을 제거할 수 없습니다"
            );
        }
    }

    private List<UUID> normalizedCopyIds(List<UUID> ids, String field) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SUCCESSOR_COPY_COUNT) {
            throw new DomainValidationException(field + "은(는) 100개를 넘을 수 없습니다");
        }
        Set<UUID> uniqueIds = new HashSet<>();
        for (UUID id : ids) {
            if (id == null) {
                throw new DomainValidationException(field + " 식별자는 null일 수 없습니다");
            }
            if (!uniqueIds.add(id)) {
                throw new DomainValidationException(field + "은(는) 중복될 수 없습니다");
            }
        }
        return uniqueIds.stream().sorted().toList();
    }

    private List<Role> selectSourceRoles(
            UUID teamId,
            UUID sourceSeasonId,
            List<UUID> roleIds
    ) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Role> rolesById = new HashMap<>();
        for (Role role : repository.findRolesByTeamIdAndSeasonId(teamId, sourceSeasonId)) {
            rolesById.put(role.getId(), role);
        }
        List<Role> selected = new ArrayList<>();
        for (UUID roleId : roleIds) {
            Role role = rolesById.get(roleId);
            if (role == null) {
                throw notFound("ROLE_NOT_FOUND", "복사할 역할을 찾을 수 없습니다");
            }
            selected.add(role);
        }
        return selected;
    }

    private List<Routine> selectSourceRoutines(UUID sourceSeasonId, List<UUID> routineIds) {
        if (routineIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Routine> routinesById = new HashMap<>();
        for (Routine routine : repository.findRoutinesBySeasonId(sourceSeasonId)) {
            routinesById.put(routine.getId(), routine);
        }
        List<Routine> selected = new ArrayList<>();
        for (UUID routineId : routineIds) {
            Routine routine = routinesById.get(routineId);
            if (routine == null) {
                throw notFound("ROUTINE_NOT_FOUND", "복사할 루틴을 찾을 수 없습니다");
            }
            selected.add(routine);
        }
        return selected;
    }

    private NextSeasonResult toNextSeasonResult(Season sourceSeason, Season targetSeason) {
        List<CopiedRoleResult> copiedRoles = repository
                .findRolesByTeamIdAndSeasonId(targetSeason.getTeamId(), targetSeason.getId())
                .stream()
                .filter(role -> role.getPreviousRoleId() != null)
                .map(role -> new CopiedRoleResult(role.getPreviousRoleId(), role.getId()))
                .sorted((left, right) -> left.sourceRoleId().compareTo(right.sourceRoleId()))
                .toList();
        List<CopiedRoutineResult> copiedRoutines = repository
                .findRoutinesBySeasonId(targetSeason.getId())
                .stream()
                .filter(routine -> routine.getPreviousRoutineId() != null)
                .map(routine -> new CopiedRoutineResult(
                        routine.getPreviousRoutineId(),
                        routine.getId()
                ))
                .sorted((left, right) ->
                        left.sourceRoutineId().compareTo(right.sourceRoutineId()))
                .toList();
        return new NextSeasonResult(
                toSeasonResult(sourceSeason),
                toSeasonResult(targetSeason),
                copiedRoles,
                copiedRoutines
        );
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
        return new SeasonResult(
                season.getId(),
                season.getName(),
                season.getStartDate(),
                season.getEndDate(),
                season.getEndedAt(),
                season.getPreviousSeasonId(),
                season.getTimeZone(),
                toRoundScheduleResult(season)
        );
    }

    private SeasonSummaryResult toSeasonSummaryResult(Season season) {
        return new SeasonSummaryResult(
                season.getId(),
                season.getName(),
                season.getStartDate(),
                season.getEndDate(),
                season.getEndedAt(),
                season.getPreviousSeasonId(),
                season.getTimeZone(),
                toRoundScheduleResult(season)
        );
    }

    private RoundScheduleResult toRoundScheduleResult(Season season) {
        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule == null) {
            return null;
        }
        return new RoundScheduleResult(
                season.getTimeZone(),
                schedule.getFirstMeetingDate(),
                schedule.getMeetingTime(),
                schedule.getRecurrence(),
                schedule.getGenerationLeadDays(),
                schedule.isEnabled(),
                schedule.getNextOccurrenceDate()
        );
    }

    private MemberResult toMemberResult(Member member) {
        int codePoint = member.getName().codePointAt(0);
        String initials = new String(Character.toChars(codePoint));
        String tone = MEMBER_TONES[Math.floorMod(member.getId().hashCode(), MEMBER_TONES.length)];
        return new MemberResult(
                member.getId(),
                member.getName(),
                initials,
                tone,
                member.getDeactivatedAt()
        );
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

    private RoleHandoffTransitionResult toRoleHandoffTransitionResult(
            Role role,
            RoleHandoff handoff
    ) {
        return new RoleHandoffTransitionResult(
                toRoleResult(role),
                toRoleHandoffResult(handoff)
        );
    }

    private RoleHandoffResult toRoleHandoffResult(RoleHandoff handoff) {
        return new RoleHandoffResult(
                handoff.getId(),
                handoff.getRoleId(),
                handoff.getFromMemberId(),
                handoff.getToMemberId(),
                handoff.getOutgoingAssignmentStartDate(),
                handoff.getOutgoingAssignmentEndDate(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate(),
                handoff.getStatus(),
                handoff.getPreparedAt(),
                handoff.getTransferredAt(),
                handoff.getAcceptedAt(),
                handoff.getCancelledAt(),
                handoff.getTransferredByMemberId(),
                handoff.getAcceptedByMemberId(),
                handoff.getCancelledByMemberId(),
                handoff.getSnapshotItemCount(),
                handoff.getSnapshotIncompleteItemCount(),
                handoff.getSnapshotResourceCount(),
                handoff.isWarningAcknowledged()
        );
    }

    private RoutineResult toRoutineResult(Routine routine) {
        return new RoutineResult(
                routine.getId(),
                routine.getTitle(),
                routine.getPhase(),
                routine.getDueLabel(),
                routine.getOwnerRoleId(),
                routine.getDetail(),
                routine.getDeadlineDayOffset(),
                routine.getDeadlineTime()
        );
    }

    private SeasonRoundResult toSeasonRoundResult(
            SeasonRound round,
            List<RoutineExecution> executions,
            Season season
    ) {
        ZoneId zoneId = season.getZoneId();
        List<RoutineExecutionResult> executionResults = executions.stream()
                .map(execution -> toRoutineExecutionResult(execution, zoneId))
                .toList();
        return new SeasonRoundResult(
                round.getId(),
                round.getName(),
                round.getMeetingDate(),
                executionResults,
                round.getArchivedAt(),
                round.getOrigin(),
                round.getScheduledOccurrenceDate(),
                round.getScheduledAt(),
                roundTimingStatus(round, executionResults)
        );
    }

    private RoutineExecutionResult toRoutineExecutionResult(
            RoutineExecution execution,
            ZoneId zoneId
    ) {
        return new RoutineExecutionResult(
                execution.getId(),
                execution.getSeasonRoundId(),
                execution.getRoutineId(),
                execution.getTitle(),
                execution.getPhase(),
                execution.getDueLabel(),
                execution.getOwnerRoleId(),
                execution.getStatus(),
                execution.getDetail(),
                execution.getDeadlineAt(),
                execution.timingStatus(clock, zoneId)
        );
    }

    private RoundTimingStatus roundTimingStatus(
            SeasonRound round,
            List<RoutineExecutionResult> executions
    ) {
        if (!executions.isEmpty()
                && executions.stream()
                .allMatch(execution -> execution.timingStatus() == RoutineTimingStatus.COMPLETED)) {
            return RoundTimingStatus.COMPLETED;
        }
        if (executions.stream()
                .anyMatch(execution -> execution.timingStatus() == RoutineTimingStatus.OVERDUE)) {
            return RoundTimingStatus.OVERDUE;
        }
        if (executions.stream().anyMatch(execution ->
                execution.timingStatus() == RoutineTimingStatus.IN_PROGRESS
                        || execution.timingStatus() == RoutineTimingStatus.COMPLETED)) {
            return RoundTimingStatus.IN_PROGRESS;
        }
        if (executions.isEmpty()
                && round.getScheduledAt() != null
                && !Instant.now(clock).isBefore(round.getScheduledAt())) {
            return RoundTimingStatus.IN_PROGRESS;
        }
        return RoundTimingStatus.PLANNED;
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
            String idempotencyKey
    ) {
        List<String> values = List.of(teamId.toString(), idempotencyKey);
        String idempotencyHash = HexFormat.of().formatHex(hashDomainValues(idempotencyHashDomain, values));
        String accessKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hashDomainValues(accessKeyDomain, values));
        return new AccessKeyChange(idempotencyHash, accessKey);
    }

    private AccessKeyChange deriveLegacyAccessKeyChange(
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

    private String fingerprintNextSeasonRequest(
            UUID teamId,
            UUID sourceSeasonId,
            Season targetSeason,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.SEASON,
                teamId,
                sourceSeasonId
        );
        updateDigest(digest, targetSeason.getName());
        updateDigest(digest, targetSeason.getStartDate().toString());
        updateDigest(digest, targetSeason.getEndDate().toString());
        updateDigest(digest, Integer.toString(roleIds.size()));
        for (UUID roleId : roleIds) {
            updateDigest(digest, roleId.toString());
        }
        updateDigest(digest, Integer.toString(routineIds.size()));
        for (UUID routineId : routineIds) {
            updateDigest(digest, routineId.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintMemberRequest(UUID teamId, UUID seasonId, Member member) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.MEMBER, teamId, seasonId);
        updateDigest(digest, member.getName());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintRoutineRequest(UUID teamId, UUID seasonId, Routine routine) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROUTINE, teamId, seasonId);
        updateDigest(digest, routine.getTitle());
        updateDigest(digest, routine.getPhase().name());
        updateDigest(digest, routine.getDueLabel());
        updateDigest(digest, routine.getOwnerRoleId().toString());
        updateDigest(digest, routine.getDetail());
        if (routine.getDeadlineDayOffset() != null) {
            updateNullableDigest(digest, routine.getDeadlineDayOffset());
            updateNullableDigest(digest, routine.getDeadlineTime());
        }
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

    private String fingerprintRoleHandoffRequest(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            PrepareRoleHandoffCommand command
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.ROLE_HANDOFF,
                teamId,
                seasonId
        );
        updateDigest(digest, roleId.toString());
        updateDigest(digest, command.toMemberId().toString());
        updateDigest(digest, command.incomingAssignmentStartDate().toString());
        updateNullableDigest(digest, command.incomingAssignmentEndDate());
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

    private RoleHandoffStateConflictException handoffStateConflict(String message) {
        return new RoleHandoffStateConflictException(message);
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
