package com.personal.baton.application.workspace;

import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamAccessAudit;
import com.personal.baton.domain.workspace.TeamInvitation;
import com.personal.baton.domain.workspace.TeamPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TeamAccessService implements TeamAccessUseCase {
    private final WorkspaceAccessRepository teams;
    private final WorkspacePeopleRepository people;
    private final WorkspaceSeasonRepository seasons;
    private final RoundAuthorizationRepository memberships;
    private final TeamAccessRepository access;
    private final WorkspaceAccessControl secrets;
    private final TeamAccountAccessPolicy policy;
    private final CurrentAccountProvider accounts;
    private final IdentityRepository identities;
    private final Clock clock;
    private final CalendarSubscriptionStore calendarSubscriptions;
    private final StringKeyGenerator tokenGenerator;
    public TeamAccessService(WorkspaceAccessRepository teams, WorkspacePeopleRepository people,
            WorkspaceSeasonRepository seasons, RoundAuthorizationRepository memberships, TeamAccessRepository access,
            WorkspaceAccessControl secrets, TeamAccountAccessPolicy policy, CurrentAccountProvider accounts, Clock clock, CalendarSubscriptionStore calendarSubscriptions, IdentityRepository identities,
            StringKeyGenerator tokenGenerator) {
        this.teams = teams;
        this.people = people;
        this.seasons = seasons;
        this.memberships = memberships;
        this.access = access;
        this.secrets = secrets;
        this.policy = policy;
        this.accounts = accounts;
        this.clock = clock;
        this.identities = identities;
        this.calendarSubscriptions = calendarSubscriptions;
        this.tokenGenerator = tokenGenerator;
    }
    @Override
    public MyTeamsResult getMyTeams(UUID accountId) {
        requireActor(accountId);
        return new MyTeamsResult(accountId, access.findAccountTeams(accountId));
    }
    @Override
    public TeamAccessResult getAccess(UUID teamId, UUID accountId, String accessKey) {
        requireActor(accountId);
        Team team = teams.findTeamById(teamId).orElseThrow(this::teamNotFound);
        if (team.isAccountAccessEnabled()) policy.requireRead(team);
        else secrets.verifyAccessKey(team, accessKey);
        return result(team, accountId);
    }
    @Override
    @Transactional
    public TeamAccessResult activate(UUID teamId, UUID accountId, UUID memberId, String recoveryKey) {
        requireActor(accountId);
        secrets.verifyWorkspaceRecoveryPermission(recoveryKey);
        Team team = lockTeam(teamId);
        requireActiveAccount(accountId);
        requireActiveMember(teamId, memberId);
        AccountTeamMembership membership = memberships.findMembership(accountId, teamId)
                .filter(value -> value.getMemberId().equals(memberId)).orElseThrow(WorkspaceAccessDeniedException::new);
        TeamPermission previous = membership.getPermission();
        team.enableAccountAccess();
        teams.saveTeam(team);
        membership.changePermission(TeamPermission.ADMIN);
        access.saveMembership(membership);
        calendarSubscriptions.requestUnauthorizedRevocations(teamId);
        revokePending(teamId, memberId);
        audit(teamId, accountId, memberId, "ADMIN_RECOVERY", previous, TeamPermission.ADMIN);
        return result(team, accountId);
    }
    @Override
    @Transactional
    public CreatedInvitationResult invite(UUID teamId, UUID accountId, UUID memberId, TeamPermission permission) {
        requireActor(accountId);
        Team team = requireAdministrator(teamId);
        requireActiveMember(teamId, memberId);
        if (access.findMembershipByMemberId(memberId).filter(value -> value.getTeamId().equals(teamId))
                .filter(value -> value.getPermission() != null).isPresent())
            throw new DomainValidationException("이미 접근 권한이 있는 구성원은 권한 목록에서 변경해 주세요");
        revokePending(teamId, memberId);
        String token = tokenGenerator.generateKey();
        Instant now = clock.instant();
        TeamInvitation invitation = access.saveInvitation(TeamInvitation.create(teamId, memberId, tokenHash(token),
                permission, accountId, now, now.plus(Duration.ofDays(7))));
        audit(team.getId(), accountId, memberId, "INVITED", null, permission);
        return new CreatedInvitationResult(invitationResult(invitation), token);
    }
    @Override
    @Transactional
    public TeamAccessResult revokeInvitation(UUID teamId, UUID accountId, UUID invitationId) {
        requireActor(accountId);
        Team team = requireAdministrator(teamId);
        TeamInvitation invitation = access.findInvitation(invitationId).filter(value -> value.getTeamId().equals(teamId))
                .orElseThrow(this::invitationNotFound);
        if (invitation.getRevokedAt() == null && invitation.getAcceptedAt() == null) {
            invitation.revoke(clock.instant());
            access.saveInvitation(invitation);
            audit(teamId, accountId, invitation.getMemberId(), "INVITATION_REVOKED", null, invitation.getPermission());
        }
        return result(team, accountId);
    }
    @Override
    @Transactional
    public TeamAccessResult changePermission(UUID teamId, UUID accountId, UUID memberId, TeamPermission permission) {
        requireActor(accountId);
        Team team = requireAdministrator(teamId);
        var membership = access.findMembershipByMemberId(memberId).filter(value -> value.getTeamId().equals(teamId))
                .orElseThrow(() -> new WorkspaceNotFoundException("MEMBERSHIP_NOT_FOUND", "연결된 계정을 찾을 수 없습니다"));
        if (permission != null) {
            requireActiveMember(teamId, memberId);
            requireActiveAccount(membership.getAccountId());
        }
        if (permission != TeamPermission.ADMIN) policy.requireOtherAdministrator(team, memberId);
        TeamPermission previous = membership.getPermission();
        membership.changePermission(permission);
        access.saveMembership(membership);
        revokePending(teamId, memberId);
        if (permission == null) calendarSubscriptions.requestMemberRevocation(teamId, memberId);
        if (previous != permission) audit(teamId, accountId, memberId, "PERMISSION_CHANGED", previous, permission);
        return result(team, accountId);
    }
    @Override
    public InvitationPreviewResult preview(UUID accountId, String token) {
        requireActor(accountId);
        TeamInvitation invitation = access.findInvitationByTokenHash(tokenHash(token)).orElseThrow(this::invitationNotFound);
        boolean acceptedByMe = accountId.equals(invitation.getAcceptedBy());
        if (!invitation.isPending(clock.instant()) && !acceptedByMe) throw invitationNotFound();
        Team team = teams.findTeamById(invitation.getTeamId()).orElseThrow(this::teamNotFound);
        if (!team.isAccountAccessEnabled()) throw invitationNotFound();
        Member member = requireActiveMember(team.getId(), invitation.getMemberId());
        TeamPermission permission = invitation.getPermission();
        if (acceptedByMe) {
            permission = memberships.findMembership(accountId, team.getId())
                    .filter(value -> value.getMemberId().equals(member.getId()) && value.getPermission() != null)
                    .orElseThrow(WorkspaceAccessDeniedException::new).getPermission();
        }
        return new InvitationPreviewResult(team.getId(), team.getName(), member.getId(), member.getName(),
                permission, invitation.getExpiresAt());
    }
    @Override
    @Transactional
    public InvitationAcceptedResult accept(UUID accountId, String token) {
        requireActor(accountId);
        String hash = tokenHash(token);
        UUID teamId = access.findInvitationTeamId(hash).orElseThrow(this::invitationNotFound);
        Team team = lockTeam(teamId);
        requireActiveAccount(accountId);
        if (!team.isAccountAccessEnabled()) throw invitationNotFound();
        TeamInvitation invitation = access.lockInvitation(hash).orElseThrow(this::invitationNotFound);
        Member member = requireActiveMember(teamId, invitation.getMemberId());
        if (invitation.getAcceptedAt() != null) {
            if (!accountId.equals(invitation.getAcceptedBy())) throw invitationNotFound();
            var membership = memberships.findMembership(accountId, teamId)
                    .filter(value -> value.getMemberId().equals(member.getId()) && value.getPermission() != null)
                    .orElseThrow(WorkspaceAccessDeniedException::new);
            return accepted(team, membership);
        }
        if (!invitation.isPending(clock.instant())) throw invitationNotFound();
        AccountTeamMembership membership = memberships.findMembership(accountId, teamId)
                .or(() -> access.findMembershipByMemberId(member.getId()))
                .orElseGet(() -> AccountTeamMembership.create(UUID.randomUUID(), accountId, teamId,
                        member.getId(), clock.instant()));
        if (!membership.getAccountId().equals(accountId) || !membership.getMemberId().equals(member.getId()))
            throw new AccountMembershipConflictException("이미 다른 계정 또는 구성원으로 연결되어 있습니다");
        TeamPermission previous = membership.getPermission();
        membership.changePermission(invitation.getPermission());
        access.saveMembership(membership);
        invitation.accept(accountId, clock.instant());
        access.saveInvitation(invitation);
        audit(teamId, accountId, member.getId(), "INVITATION_ACCEPTED", previous, membership.getPermission());
        return accepted(team, membership);
    }
    private InvitationAcceptedResult accepted(Team team, AccountTeamMembership membership) {
        var season = seasons.findLatestSeasonByTeamId(team.getId()).orElseThrow(this::teamNotFound);
        return new InvitationAcceptedResult(membership.getAccountId(), team.getId(), season.getId(),
                membership.getMemberId(), membership.getPermission());
    }
    private Team requireAdministrator(UUID teamId) {
        Team team = lockTeam(teamId);
        if (!team.isAccountAccessEnabled()) throw new DomainValidationException("운영자가 먼저 관리자와 계정 권한을 설정해야 합니다");
        policy.requireAdministrator(team); return team;
    }
    private Team lockTeam(UUID teamId) { return teams.findTeamByIdForUpdate(teamId).orElseThrow(this::teamNotFound); }
    private void requireActiveAccount(UUID accountId) {
        identities.findAccountByIdForUpdate(accountId).filter(Account::isActive).orElseThrow(AccountDeactivatedException::new);
    }
    private void requireActor(UUID accountId) {
        if (accountId == null || accounts.currentAccountId().filter(accountId::equals).isEmpty()) throw new WorkspaceAccessDeniedException();
    }
    private Member requireActiveMember(UUID teamId, UUID memberId) {
        return people.findMemberById(memberId).filter(member -> member.getTeamId().equals(teamId)).filter(Member::isActive)
                .orElseThrow(() -> new DomainValidationException("활동 중인 팀 구성원을 선택해 주세요"));
    }
    private void revokePending(UUID teamId, UUID memberId) {
        for (TeamInvitation invitation : access.findUnacceptedInvitations(teamId, memberId)) {
            invitation.revoke(clock.instant());
            access.saveInvitation(invitation);
        }
    }
    private void audit(UUID teamId, UUID actor, UUID memberId, String action, TeamPermission previous, TeamPermission permission) {
        access.saveAudit(TeamAccessAudit.create(teamId, actor, memberId, action, previous, permission, clock.instant()));
    }
    private TeamAccessResult result(Team team, UUID accountId) {
        var mine = memberships.findMembership(accountId, team.getId()).orElse(null);
        boolean administrator = mine != null && mine.getPermission() == TeamPermission.ADMIN;
        List<MemberAccessResult> members = List.of();
        if (administrator || !team.isAccountAccessEnabled()) {
            Map<UUID, AccountTeamMembership> byMember = access.findMemberships(team.getId()).stream()
                    .collect(Collectors.toMap(AccountTeamMembership::getMemberId, Function.identity()));
            members = people.findMembersByTeamId(team.getId()).stream().map(member -> {
                var claim = byMember.get(member.getId());
                return new MemberAccessResult(member.getId(), member.getName(), member.isActive(),
                        claim == null ? null : claim.getAccountId(), claim == null ? null : claim.getPermission());
            }).toList();
        }
        return new TeamAccessResult(team.getId(), accountId, team.isAccountAccessEnabled(), mine == null ? null : mine.getMemberId(),
                mine == null ? null : mine.getPermission(), members,
                administrator ? access.findInvitations(team.getId()).stream().map(this::invitationResult).toList() : List.of(),
                administrator ? access.findAudit(team.getId()).stream().map(value -> new AccessAuditResult(value.getId(),
                        value.getActorAccountId(), value.getMemberId(), value.getAction(), value.getPreviousPermission(),
                        value.getPermission(), value.getChangedAt())).toList() : List.of());
    }
    private InvitationResult invitationResult(TeamInvitation value) {
        return new InvitationResult(value.getId(), value.getMemberId(), value.getPermission(), value.getCreatedAt(),
                value.getExpiresAt(), value.getAcceptedAt(), value.getRevokedAt());
    }
    private String tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invitationNotFound();
        return DomainSeparatedSha256.hashHex("baton:team-invitation:v1", List.of(token));
    }
    private WorkspaceNotFoundException invitationNotFound() { return new WorkspaceNotFoundException("TEAM_INVITATION_NOT_FOUND", "사용할 수 있는 초대가 없습니다"); }
    private WorkspaceNotFoundException teamNotFound() { return new WorkspaceNotFoundException("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"); }
}
