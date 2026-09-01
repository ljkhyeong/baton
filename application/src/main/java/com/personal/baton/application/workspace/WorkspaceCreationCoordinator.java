package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
final class WorkspaceCreationCoordinator {

    private static final String IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-idempotency:v1";
    private static final String REQUEST_FINGERPRINT_DOMAIN =
            "baton:workspace-request:v1";

    private final WorkspaceRepository repository;
    private final WorkspaceAccessControl accessControl;
    private final CalendarChangeRecorder calendarChangeRecorder;

    WorkspaceCreationCoordinator(
            WorkspaceRepository repository,
            WorkspaceAccessControl accessControl,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.calendarChangeRecorder = calendarChangeRecorder;
    }

    CreatedWorkspaceResult create(
            String idempotencyKey,
            CreateWorkspaceCommand command
    ) {
        String accessKey = accessControl.deriveInitialAccessKey(idempotencyKey);
        String idempotencyKeyHash = DomainSeparatedSha256.hashHex(
                IDEMPOTENCY_HASH_DOMAIN,
                List.of(idempotencyKey)
        );
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
        String requestFingerprint = fingerprintCreationRequest(team, season, members);

        Team existing = repository.findTeamByIdempotencyKeyHash(idempotencyKeyHash).orElse(null);
        if (existing != null) {
            if (!requestFingerprint.equals(existing.getCreationRequestFingerprint())) {
                throw new IdempotencyKeyReusedException();
            }
            if (!accessControl.matchesAccessKey(existing, accessKey)) {
                throw new IdempotencyReplayExpiredException();
            }
            Season existingSeason = repository.findSeasonById(existing.getCreationSeasonId())
                    .filter(found -> found.getTeamId().equals(existing.getId()))
                    .orElseThrow(() ->
                            new IllegalStateException("멱등 생성된 팀의 생성 시즌을 찾을 수 없습니다"));
            return new CreatedWorkspaceResult(
                    existing.getId(),
                    existingSeason.getId(),
                    accessKey
            );
        }

        team.recordCreationRequest(idempotencyKeyHash, requestFingerprint, seasonId);
        repository.saveTeam(team);
        repository.saveSeason(season);
        calendarChangeRecorder.recordSeason(season);
        repository.saveMembers(members);
        return new CreatedWorkspaceResult(teamId, seasonId, accessKey);
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

    private String fingerprintCreationRequest(
            Team team,
            Season season,
            List<Member> members
    ) {
        DomainSeparatedSha256 fingerprint = DomainSeparatedSha256
                .inDomain(REQUEST_FINGERPRINT_DOMAIN)
                .append(team.getName())
                .append(season.getName())
                .append(season.getStartDate().toString())
                .append(season.getEndDate().toString());
        List<String> normalizedMemberNames = members.stream()
                .map(Member::getName)
                .sorted()
                .toList();
        fingerprint.append(Integer.toString(normalizedMemberNames.size()));
        for (String memberName : normalizedMemberNames) {
            fingerprint.append(memberName);
        }
        return fingerprint.digestHex();
    }
}
