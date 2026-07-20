package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService implements WorkspaceUseCase {

    private static final String[] MEMBER_TONES = {
            "#d9e4da", "#f1d6cc", "#d8dfee", "#eee3bf", "#dce7ef", "#eadcf0"
    };

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkspaceService(WorkspaceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreatedWorkspaceResult createWorkspace(CreateWorkspaceCommand command) {
        String accessKey = generateAccessKey();
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

        repository.saveTeam(team);
        repository.saveSeason(season);
        repository.saveMembers(members);
        return new CreatedWorkspaceResult(teamId, seasonId, accessKey);
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
        Team team = repository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        verifyAccessKey(team, accessKey);
        Season season = repository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        return new AuthorizedScope(team, season);
    }

    private void verifyAccessKey(Team team, String accessKey) {
        if (accessKey == null || accessKey.isBlank()) {
            throw new WorkspaceAccessDeniedException();
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(team.getAccessKeyHash());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 접근 키 해시가 올바르지 않습니다", exception);
        }
        byte[] actual = sha256(accessKey);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    private List<Member> createMembers(UUID teamId, List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            throw new DomainValidationException("구성원은 한 명 이상이어야 합니다");
        }
        List<Member> members = new ArrayList<>();
        for (String memberName : memberNames) {
            members.add(Member.create(UUID.randomUUID(), teamId, memberName));
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

    private String generateAccessKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashAccessKeyHex(String accessKey) {
        return HexFormat.of().formatHex(sha256(accessKey));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }

    private record AuthorizedScope(Team team, Season season) {
    }
}
