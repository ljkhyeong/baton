package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChange;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChangeKind;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.LegacyAccessKey;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService implements WorkspaceUseCase {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{32,200}");
    private static final String IDEMPOTENCY_HASH_DOMAIN = "baton:workspace-idempotency:v1";
    private static final String REQUEST_FINGERPRINT_DOMAIN = "baton:workspace-request:v1";
    private static final String OWNER_REQUEST_FINGERPRINT_DOMAIN =
            "baton:workspace-owner-request:v1";
    private final WorkspaceRepository repository;
    private final MemberIdentityUseCase memberIdentityUseCase;
    private final Clock clock;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceProjectionReader projectionReader;
    private final WorkspaceAccessControl accessControl;
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceSeasonLifecycle seasonLifecycle;

    @Autowired
    public WorkspaceService(
            WorkspaceRepository repository,
            MemberIdentityUseCase memberIdentityUseCase,
            Clock clock,
            @Value("${baton.workspace.creation-key:}") String workspaceCreationKey,
            @Value("${baton.workspace.recovery-key:}") String workspaceRecoveryKey
    ) {
        this.repository = repository;
        this.memberIdentityUseCase = memberIdentityUseCase;
        this.clock = clock;
        this.resultMapper = new WorkspaceResultMapper(clock);
        this.projectionReader = new WorkspaceProjectionReader(repository, clock, resultMapper);
        this.accessControl = new WorkspaceAccessControl(
                workspaceCreationKey,
                workspaceRecoveryKey
        );
        this.scopeAuthorizer = new WorkspaceScopeAuthorizer(repository, accessControl);
        this.contentIdempotency = new WorkspaceContentIdempotency(repository);
        this.seasonLifecycle = new WorkspaceSeasonLifecycle(
                repository,
                clock,
                resultMapper,
                contentIdempotency
        );
    }

    public WorkspaceService(
            WorkspaceRepository repository,
            Clock clock,
            String workspaceCreationKey,
            String workspaceRecoveryKey
    ) {
        this(
                repository,
                null,
                clock,
                workspaceCreationKey,
                workspaceRecoveryKey
        );
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command
    ) {
        return createWorkspace(
                idempotencyKey,
                creationKey,
                command,
                null,
                null
        );
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspaceForOwner(
            String idempotencyKey,
            AuthenticatedAccount authenticatedAccount,
            String ownerMemberName,
            CreateWorkspaceCommand command
    ) {
        Objects.requireNonNull(authenticatedAccount, "인증 사용자 계정은 필수입니다");
        Objects.requireNonNull(
                memberIdentityUseCase,
                "세션 워크스페이스 생성에는 구성원 신원 결속 서비스가 필요합니다"
        );
        return createWorkspace(
                idempotencyKey,
                null,
                command,
                authenticatedAccount,
                ownerMemberName
        );
    }

    private CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String creationKey,
            CreateWorkspaceCommand command,
            AuthenticatedAccount authenticatedAccount,
            String ownerMemberName
    ) {
        requireValidIdempotencyKey(idempotencyKey);
        if (authenticatedAccount == null) {
            accessControl.verifyWorkspaceCreationPermission(creationKey);
        }

        String accessKey = authenticatedAccount == null
                ? accessControl.deriveInitialAccessKey(idempotencyKey)
                : accessControl.generateInternalAccessKey();
        String idempotencyKeyHash = hashDomainValueHex(IDEMPOTENCY_HASH_DOMAIN, idempotencyKey);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();

        Team team = Team.create(
                teamId,
                command.teamName(),
                accessControl.hashAccessKey(accessKey)
        );
        Season season = Season.create(
                seasonId,
                teamId,
                command.seasonName(),
                command.startDate(),
                command.endDate()
        );
        List<Member> members = createMembers(teamId, command.memberNames());
        Member owner = authenticatedAccount == null
                ? null
                : requireInitialOwner(members, ownerMemberName);
        String requestFingerprint = authenticatedAccount == null
                ? fingerprintCreationRequest(team, season, members)
                : fingerprintOwnerCreationRequest(
                        team,
                        season,
                        members,
                        authenticatedAccount,
                        owner
                );

        Team existing = repository.findTeamByIdempotencyKeyHash(idempotencyKeyHash).orElse(null);
        if (existing != null) {
            if (!requestFingerprint.equals(existing.getCreationRequestFingerprint())) {
                throw new IdempotencyKeyReusedException();
            }
            if (authenticatedAccount == null && !accessControl.matchesAccessKey(existing, accessKey)) {
                throw new IdempotencyReplayExpiredException();
            }
            Season existingSeason = repository.findSeasonById(existing.getCreationSeasonId())
                    .filter(found -> found.getTeamId().equals(existing.getId()))
                    .orElseThrow(() -> new IllegalStateException("멱등 생성된 팀의 생성 시즌을 찾을 수 없습니다"));
            return new CreatedWorkspaceResult(
                    existing.getId(),
                    existingSeason.getId(),
                    authenticatedAccount == null ? accessKey : null
            );
        }

        team.recordCreationRequest(idempotencyKeyHash, requestFingerprint, seasonId);
        repository.saveTeam(team);
        repository.saveSeason(season);
        repository.saveMembers(members);
        if (authenticatedAccount != null) {
            memberIdentityUseCase.bindInitialOwner(
                    teamId,
                    owner.getId(),
                    authenticatedAccount
            );
        }
        return new CreatedWorkspaceResult(
                teamId,
                seasonId,
                authenticatedAccount == null ? accessKey : null
        );
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
        WorkspaceScope scope = scopeAuthorizer.requireScope(teamId, seasonId);
        AccessKeyChange change = accessControl.deriveAccessKeyChange(
                AccessKeyChangeKind.ROTATE,
                teamId,
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacyAccessKeyChange(
                scope.team(),
                AccessKeyChangeKind.ROTATE,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
        }
        accessControl.verifyAccessKey(scope.team(), currentAccessKey);
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
        accessControl.verifyWorkspaceRecoveryPermission(recoveryKey);
        requireValidIdempotencyKey(idempotencyKey);
        WorkspaceScope scope = scopeAuthorizer.requireScope(teamId, seasonId);
        AccessKeyChange change = accessControl.deriveAccessKeyChange(
                AccessKeyChangeKind.RECOVER,
                teamId,
                idempotencyKey
        );
        AccessKeyResult replay = replayAccessKeyChange(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacyAccessKeyChange(
                scope.team(),
                AccessKeyChangeKind.RECOVER,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
        }
        return replaceAccessKey(scope.team(), change);
    }

    @Override
    public WorkspaceResult getWorkspaceAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        AuthorizedScope scope = authorize(teamId, seasonId, authorization);
        return projectionReader.read(new WorkspaceScope(scope.team(), scope.season()));
    }

    @Override
    @Transactional(readOnly = true)
    public RoleResourceResult getRoleResourceForGrantAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization
    ) {
        authorizeExternalGrant(teamId, seasonId, authorization);
        RoleResource resource = repository.findRoleResourceByIdWithSharedLock(resourceId)
                .orElseThrow(() -> notFound(
                        "ROLE_RESOURCE_NOT_FOUND",
                        "자료를 찾을 수 없습니다"
                ));
        repository.findRoleById(resource.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound(
                        "ROLE_RESOURCE_NOT_FOUND",
                        "자료를 찾을 수 없습니다"
                ));
        return resultMapper.toRoleResourceResult(resource);
    }

    @Override
    @Transactional
    public SeasonResult updateSeasonAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateSeasonCommand command
    ) {
        AuthorizedScope scope = authorizeSeasonForUpdate(teamId, seasonId, authorization);
        return seasonLifecycle.updateSeason(
                teamId,
                seasonId,
                new WorkspaceScope(scope.team(), scope.season()),
                command
        );
    }

    @Override
    @Transactional
    public SeasonResult updateRoundScheduleAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            UpdateRoundScheduleCommand command
    ) {
        AuthorizedScope scope = authorizeSeasonForUpdate(teamId, seasonId, authorization);
        return seasonLifecycle.updateRoundSchedule(
                seasonId,
                new WorkspaceScope(scope.team(), scope.season()),
                command
        );
    }

    @Override
    @Transactional
    public SeasonResult updateSeasonEndingAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization,
            boolean ended
    ) {
        AuthorizedScope scope = authorizeSeasonLifecycle(teamId, seasonId, authorization);
        return seasonLifecycle.updateSeasonEnding(
                teamId,
                seasonId,
                new WorkspaceScope(scope.team(), scope.season()),
                ended
        );
    }

    @Override
    @Transactional
    public NextSeasonResult createNextSeasonAuthorized(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateNextSeasonCommand command
    ) {
        requireValidIdempotencyKey(idempotencyKey);
        AuthorizedScope scope = authorizeSeasonLifecycle(
                teamId,
                sourceSeasonId,
                authorization
        );
        return seasonLifecycle.createNextSeason(
                teamId,
                sourceSeasonId,
                idempotencyKey,
                new WorkspaceScope(scope.team(), scope.season()),
                command
        );
    }

    @Override
    @Transactional
    public MemberResult createMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateMemberCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        requireValidIdempotencyKey(idempotencyKey);
        Member member = Member.create(UUID.randomUUID(), teamId, command.name());
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.MEMBER,
                idempotencyKey,
                contentIdempotency.fingerprintMemberRequest(teamId, seasonId, member),
                member.getId()
        );
        if (attempt.replayResourceId() != null) {
            Member existing = repository.findMemberById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.MEMBER));
            return resultMapper.toMemberResult(existing);
        }
        if (repository.existsMemberByTeamIdAndName(teamId, member.getName())) {
            throw new MemberNameConflictException();
        }
        contentIdempotency.reserve(attempt);
        return resultMapper.toMemberResult(repository.saveMember(member));
    }

    @Override
    @Transactional
    public MemberResult updateMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            UpdateMemberCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        Member member = requireMember(teamId, memberId);
        String normalizedName = Member.normalizeName(command.name());
        if (repository.existsMemberByTeamIdAndNameAndIdNot(teamId, normalizedName, memberId)) {
            throw new MemberNameConflictException();
        }
        member.rename(normalizedName);
        return resultMapper.toMemberResult(repository.saveMember(member));
    }

    @Override
    @Transactional
    public MemberResult updateMemberDeactivationAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            boolean deactivated
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        Member member = requireMember(teamId, memberId);
        member.updateDeactivation(deactivated, Instant.now(clock));
        return resultMapper.toMemberResult(repository.saveMember(member));
    }

    @Override
    @Transactional
    public RoleResult createRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE,
                idempotencyKey,
                contentIdempotency.fingerprintRoleRequest(teamId, seasonId, role),
                role.getId()
        );
        if (attempt.replayResourceId() != null) {
            Role existing = repository.findRoleById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROLE));
            return resultMapper.toRoleResult(existing);
        }
        requireActiveMembersForNewReferences(
                teamId,
                role.getCurrentMemberId(),
                role.getNextMemberId()
        );
        validateRoleAssignmentDates(
                scope.season(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate()
        );
        if (repository.existsRoleBySeasonIdAndName(seasonId, role.getName())) {
            throw new RoleNameConflictException();
        }
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoleResult(repository.saveRole(role));
    }

    @Override
    @Transactional
    public RoleResult updateRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            WorkspaceAuthorization authorization,
            UpdateRoleCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
                scope.season(),
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
        return resultMapper.toRoleResult(repository.saveRole(role));
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult prepareRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            PrepareRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireValidIdempotencyKey(idempotencyKey);
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        UUID handoffId = UUID.randomUUID();
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_HANDOFF,
                idempotencyKey,
                contentIdempotency.fingerprintRoleHandoffRequest(
                        teamId,
                        seasonId,
                        roleId,
                        command
                ),
                handoffId
        );
        if (attempt.replayResourceId() != null) {
            RoleHandoff existing = repository.findRoleHandoffById(attempt.replayResourceId())
                    .filter(handoff -> handoff.getTeamId().equals(teamId))
                    .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                    .filter(handoff -> handoff.getRoleId().equals(roleId))
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.ROLE_HANDOFF
                    ));
            return resultMapper.toRoleHandoffTransitionResult(role, existing);
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
        contentIdempotency.reserve(attempt);
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult transferRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            TransferRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActingMember(scope, command.confirmedByMemberId());
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
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
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
        return resultMapper.toRoleHandoffTransitionResult(
                role,
                repository.saveRoleHandoff(handoff)
        );
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult acceptRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActingMember(scope, command.confirmedByMemberId());
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
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
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
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult cancelRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActingMember(scope, command.confirmedByMemberId());
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
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
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
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    @Override
    @Transactional
    public RoutineResult createRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoutineCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROUTINE,
                idempotencyKey,
                contentIdempotency.fingerprintRoutineRequest(teamId, seasonId, routine),
                routine.getId()
        );
        if (attempt.replayResourceId() != null) {
            Routine existing = repository.findRoutineById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROUTINE));
            requireRole(teamId, seasonId, existing.getOwnerRoleId());
            return resultMapper.toRoutineResult(existing);
        }
        requireRole(teamId, seasonId, routine.getOwnerRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public RoutineResult updateRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            WorkspaceAuthorization authorization,
            UpdateRoutineCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
        return resultMapper.toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public SeasonRoundResult createSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateSeasonRoundCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROUND,
                idempotencyKey,
                contentIdempotency.fingerprintSeasonRoundRequest(teamId, seasonId, round),
                round.getId()
        );
        if (attempt.replayResourceId() != null) {
            SeasonRound existing = repository.findSeasonRoundById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROUND));
            return resultMapper.toSeasonRoundResult(
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
        contentIdempotency.reserve(attempt);
        SeasonRound savedRound = repository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return resultMapper.toSeasonRoundResult(savedRound, savedExecutions, scope.season());
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRoundAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            UpdateSeasonRoundCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
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
        return resultMapper.toSeasonRoundResult(
                saved,
                savedExecutions,
                scope.season()
        );
    }

    @Override
    @Transactional
    public SeasonRoundResult updateSeasonRoundArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            WorkspaceAuthorization authorization,
            boolean archived
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        return resultMapper.toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId()),
                scope.season()
        );
    }

    @Override
    @Transactional
    public RoutineExecutionResult updateRoutineExecutionCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            WorkspaceAuthorization authorization,
            boolean completed
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActiveSeasonRoundWithSharedLock(seasonId, roundId);
        RoutineExecution execution = repository.findRoutineExecutionById(executionId)
                .filter(found -> found.getSeasonRoundId().equals(roundId))
                .orElseThrow(() -> notFound(
                        "ROUTINE_EXECUTION_NOT_FOUND",
                        "루틴 실행 기록을 찾을 수 없습니다"
                ));
        execution.updateCompletion(completed);
        return resultMapper.toRoutineExecutionResult(
                repository.saveRoutineExecution(execution),
                scope.season().getZoneId()
        );
    }

    @Override
    @Transactional
    public DecisionResult createDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateDecisionCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActingMember(scope, command.authorMemberId());
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
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.DECISION,
                idempotencyKey,
                contentIdempotency.fingerprintDecisionRequest(teamId, seasonId, decision),
                decision.getId()
        );
        if (attempt.replayResourceId() != null) {
            Decision existing = repository.findDecisionById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.DECISION));
            Member existingAuthor = requireMember(teamId, existing.getAuthorMemberId());
            return resultMapper.toDecisionResult(
                    existing,
                    Map.of(existingAuthor.getId(), existingAuthor)
            );
        }
        Member author = requireActiveMembersForNewReferences(
                teamId,
                decision.getAuthorMemberId()
        ).get(decision.getAuthorMemberId());
        validateRoleOwnership(teamId, seasonId, decision.getRoleIds());
        contentIdempotency.reserve(attempt);
        Decision saved = repository.saveDecision(decision);
        return resultMapper.toDecisionResult(saved, Map.of(author.getId(), author));
    }

    @Override
    @Transactional
    public DecisionResult updateDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            UpdateDecisionCommand command
    ) {
        AuthorizedScope scope = authorizeMutation(teamId, seasonId, authorization);
        requireActingMember(scope, command.authorMemberId());
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
        return resultMapper.toDecisionResult(
                repository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    @Override
    @Transactional
    public DecisionResult updateDecisionArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            boolean archived
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        Decision decision = requireDecision(seasonId, decisionId);
        Member author = requireMember(teamId, decision.getAuthorMemberId());
        decision.updateArchive(archived, Instant.now(clock));
        return resultMapper.toDecisionResult(
                repository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    @Override
    @Transactional
    public HandoffItemResult createHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateHandoffItemCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        requireValidIdempotencyKey(idempotencyKey);
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                        command.roleId(),
                        command.label(),
                        command.category(),
                        false,
                        Instant.now(clock)
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.HANDOFF_ITEM,
                idempotencyKey,
                contentIdempotency.fingerprintHandoffItemRequest(teamId, seasonId, item),
                item.getId()
        );
        if (attempt.replayResourceId() != null) {
            HandoffItem existing = repository.findHandoffItemById(attempt.replayResourceId())
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(
                                    ContentCreationOperation.HANDOFF_ITEM
                            ));
            requireRole(teamId, seasonId, existing.getRoleId());
            return resultMapper.toHandoffItemResult(existing);
        }
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            UpdateHandoffItemCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(
                teamId,
                seasonId,
                item.getRoleId(),
                command.roleId()
        );
        item.update(command.roleId(), command.label(), command.category());
        return resultMapper.toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean completed
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        item.updateCompletion(completed);
        return resultMapper.toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean archived
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        HandoffItem item = requireHandoffItem(teamId, seasonId, itemId);
        requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        item.updateArchive(archived, Instant.now(clock));
        return resultMapper.toHandoffItemResult(repository.saveHandoffItem(item));
    }

    @Override
    @Transactional
    public RoleResourceResult createRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleResourceCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
        requireValidIdempotencyKey(idempotencyKey);
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                command.roleId(),
                command.title(),
                command.url(),
                command.description(),
                Instant.now(clock)
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_RESOURCE,
                idempotencyKey,
                contentIdempotency.fingerprintRoleResourceRequest(teamId, seasonId, resource),
                resource.getId()
        );
        if (attempt.replayResourceId() != null) {
            RoleResource existing = repository.findRoleResourceById(attempt.replayResourceId())
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(
                                    ContentCreationOperation.ROLE_RESOURCE
                            ));
            requireRole(teamId, seasonId, existing.getRoleId());
            return resultMapper.toRoleResourceResult(existing);
        }
        requireEditableHandoffRoles(teamId, seasonId, resource.getRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoleResourceResult(repository.saveRoleResource(resource));
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization,
            UpdateRoleResourceCommand command
    ) {
        authorizeMutation(teamId, seasonId, authorization);
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
        return resultMapper.toRoleResourceResult(repository.saveRoleResource(resource));
    }

    private AuthorizedScope authorize(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        if (authorization instanceof LegacyAccessKey legacyAccessKey) {
            WorkspaceScope scope = scopeAuthorizer.authorizeRead(
                    teamId,
                    seasonId,
                    legacyAccessKey.accessKey()
            );
            return new AuthorizedScope(scope.team(), scope.season(), null);
        }
        WorkspaceScope scope = scopeAuthorizer.requireScope(teamId, seasonId);
        UUID actingMemberId = authorizeCredential(
                teamId,
                scope.team(),
                authorization,
                false
        );
        return new AuthorizedScope(scope.team(), scope.season(), actingMemberId);
    }

    private AuthorizedScope authorizeMutation(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        if (authorization instanceof LegacyAccessKey legacyAccessKey) {
            WorkspaceScope scope = scopeAuthorizer.authorizeMutation(
                    teamId,
                    seasonId,
                    legacyAccessKey.accessKey()
            );
            return new AuthorizedScope(scope.team(), scope.season(), null);
        }
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        UUID actingMemberId = authorizeCredential(teamId, team, authorization, true);
        requireOpenSeason(season);
        return new AuthorizedScope(team, season, actingMemberId);
    }

    private AuthorizedScope authorizeExternalGrant(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        UUID actingMemberId = authorizeCredential(teamId, team, authorization, true);
        return new AuthorizedScope(team, season, actingMemberId);
    }

    private AuthorizedScope authorizeSeasonForUpdate(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        if (authorization instanceof LegacyAccessKey legacyAccessKey) {
            WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(
                    teamId,
                    seasonId,
                    legacyAccessKey.accessKey()
            );
            return new AuthorizedScope(scope.team(), scope.season(), null);
        }
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        UUID actingMemberId = authorizeCredential(teamId, team, authorization, true);
        requireOpenSeason(season);
        return new AuthorizedScope(team, season, actingMemberId);
    }

    private AuthorizedScope authorizeSeasonLifecycle(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    ) {
        if (authorization instanceof LegacyAccessKey legacyAccessKey) {
            WorkspaceScope scope = scopeAuthorizer.authorizeSeasonLifecycle(
                    teamId,
                    seasonId,
                    legacyAccessKey.accessKey()
            );
            return new AuthorizedScope(scope.team(), scope.season(), null);
        }
        Team team = repository.findTeamByIdForUpdate(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        UUID actingMemberId = authorizeCredential(teamId, team, authorization, true);
        return new AuthorizedScope(team, season, actingMemberId);
    }

    private UUID authorizeCredential(
            UUID teamId,
            Team team,
            WorkspaceAuthorization authorization,
            boolean mutation
    ) {
        if (authorization instanceof LegacyAccessKey legacyAccessKey) {
            accessControl.verifyAccessKey(team, legacyAccessKey.accessKey());
            return null;
        }
        if (authorization instanceof SessionAccount sessionAccount
                && memberIdentityUseCase != null) {
            return (mutation
                    ? memberIdentityUseCase.findActiveMemberForMutation(
                            teamId,
                            sessionAccount.authenticatedAccount()
                    )
                    : memberIdentityUseCase.findActiveMember(
                            teamId,
                            sessionAccount.authenticatedAccount()
                    ))
                    .map(MemberIdentityResult::memberId)
                    .orElseThrow(WorkspaceAccessDeniedException::new);
        }
        throw new WorkspaceAccessDeniedException();
    }

    private void requireActingMember(AuthorizedScope scope, UUID declaredMemberId) {
        if (scope.actingMemberId() != null
                && !scope.actingMemberId().equals(declaredMemberId)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    private void requireOpenSeason(Season season) {
        if (season.isEnded()) {
            throw new SeasonEndedException();
        }
    }

    private AccessKeyResult replayAccessKeyChange(
            Team team,
            AccessKeyChange change
    ) {
        if (!repository.existsAccessKeyChangeHistory(team.getId(), change.idempotencyHash())) {
            return null;
        }
        if (!change.idempotencyHash().equals(team.getLastAccessKeyChangeIdempotencyHash())) {
            throw new IdempotencyReplayExpiredException();
        }
        if (!accessControl.matchesAccessKey(team, change.accessKey())) {
            throw new IllegalStateException("저장된 접근 키 변경 결과가 멱등 키와 일치하지 않습니다");
        }
        return new AccessKeyResult(change.accessKey());
    }

    private AccessKeyResult replayLegacyAccessKeyChange(
            Team team,
            AccessKeyChangeKind kind,
            String idempotencyKey
    ) {
        boolean foundExpiredReplay = false;
        for (Season season : repository.findSeasonsByTeamId(team.getId())) {
            AccessKeyChange legacyChange =
                    accessControl.deriveLegacyAccessKeyChange(
                            kind,
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
                if (!accessControl.matchesAccessKey(team, legacyChange.accessKey())) {
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

    private AccessKeyResult replaceAccessKey(
            Team team,
            AccessKeyChange change
    ) {
        team.changeAccessKey(
                accessControl.hashAccessKey(change.accessKey()),
                change.idempotencyHash()
        );
        repository.saveTeam(team);
        repository.saveAccessKeyChangeHistory(AccessKeyChangeHistory.create(
                UUID.randomUUID(),
                team.getId(),
                change.idempotencyHash()
        ));
        return new AccessKeyResult(change.accessKey());
    }

    private void requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new DomainValidationException(
                    "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
            );
        }
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

    private Member requireInitialOwner(List<Member> members, String ownerMemberName) {
        String normalizedOwnerName = Member.normalizeName(ownerMemberName);
        return members.stream()
                .filter(member -> member.getName().equals(normalizedOwnerName))
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "OWNER 구성원은 초기 구성원 명단에서 선택해야 합니다"
                ));
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

    private Map<UUID, Member> indexMembers(List<Member> members) {
        Map<UUID, Member> result = new HashMap<>();
        for (Member member : members) {
            result.put(member.getId(), member);
        }
        return result;
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

    private String fingerprintOwnerCreationRequest(
            Team team,
            Season season,
            List<Member> members,
            AuthenticatedAccount authenticatedAccount,
            Member owner
    ) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, OWNER_REQUEST_FINGERPRINT_DOMAIN);
        updateDigest(digest, fingerprintCreationRequest(team, season, members));
        updateDigest(digest, authenticatedAccount.accountId().toString());
        updateDigest(digest, owner.getName());
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

    private record AuthorizedScope(Team team, Season season, UUID actingMemberId) {
    }
}
