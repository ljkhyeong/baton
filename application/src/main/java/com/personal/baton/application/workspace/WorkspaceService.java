package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.Season;
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
        List<Decision> decisions = repository.findDecisionsBySeasonId(seasonId);
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        List<HandoffItem> handoffItems = roleIds.isEmpty()
                ? List.of()
                : repository.findHandoffItemsByRoleIds(roleIds);

        Map<UUID, Member> membersById = indexMembers(members);
        return new WorkspaceResult(
                new TeamResult(scope.team().getId(), scope.team().getName()),
                toSeasonResult(scope.season()),
                members.stream().map(this::toMemberResult).toList(),
                roles.stream().map(this::toRoleResult).toList(),
                routines.stream().map(this::toRoutineResult).toList(),
                decisions.stream().map(decision -> toDecisionResult(decision, membersById)).toList(),
                handoffItems.stream().map(this::toHandoffItemResult).toList()
        );
    }

    @Override
    @Transactional
    public RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            CreateRoleCommand command
    ) {
        authorize(teamId, seasonId, accessKey);
        validateMemberOwnership(teamId, command.currentMemberId());
        validateMemberOwnership(teamId, command.nextMemberId());
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
        if (repository.existsRoleByTeamIdAndName(teamId, role.getName())) {
            throw new RoleNameConflictException();
        }
        return toRoleResult(repository.saveRole(role));
    }

    @Override
    @Transactional
    public RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            CreateRoutineCommand command
    ) {
        authorize(teamId, seasonId, accessKey);
        requireRole(teamId, command.ownerRoleId());
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                        command.phase(),
                        command.dueLabel(),
                        command.ownerRoleId(),
                        RoutineStatus.WAITING,
                        command.detail()
        );
        return toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public RoutineResult updateRoutineCompletion(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            boolean completed
    ) {
        authorize(teamId, seasonId, accessKey);
        Routine routine = repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));
        routine.updateCompletion(completed);
        return toRoutineResult(repository.saveRoutine(routine));
    }

    @Override
    @Transactional
    public DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            CreateDecisionCommand command
    ) {
        authorize(teamId, seasonId, accessKey);
        Member author = requireMember(teamId, command.authorMemberId());
        validateRoleOwnership(teamId, command.roleIds());
        Decision decision = Decision.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.reason(),
                command.alternative(),
                Instant.now(clock),
                author.getId(),
                command.roleIds()
        );
        Decision saved = repository.saveDecision(decision);
        return toDecisionResult(saved, Map.of(author.getId(), author));
    }

    @Override
    @Transactional
    public HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        authorize(teamId, seasonId, accessKey);
        requireRole(teamId, command.roleId());
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                        command.roleId(),
                        command.label(),
                        command.category(),
                        false
        );
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
        authorize(teamId, seasonId, accessKey);
        HandoffItem item = repository.findHandoffItemById(itemId)
                .orElseThrow(() -> notFound("HANDOFF_ITEM_NOT_FOUND", "인수인계 항목을 찾을 수 없습니다"));
        requireRole(teamId, item.getRoleId());
        item.updateCompletion(completed);
        return toHandoffItemResult(repository.saveHandoffItem(item));
    }

    private AuthorizedScope authorize(UUID teamId, UUID seasonId, String accessKey) {
        AuthorizedScope scope = requireScope(teamId, seasonId);
        verifyAccessKey(scope.team(), accessKey);
        return scope;
    }

    private AuthorizedScope requireScope(UUID teamId, UUID seasonId) {
        Team team = repository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        return new AuthorizedScope(team, season);
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
                routine.getStatus(),
                routine.getDetail()
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
                author.getName(),
                List.copyOf(decision.getRoleIds())
        );
    }

    private HandoffItemResult toHandoffItemResult(HandoffItem item) {
        return new HandoffItemResult(
                item.getId(),
                item.getRoleId(),
                item.getLabel(),
                item.getCategory(),
                item.isCompleted()
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
}
