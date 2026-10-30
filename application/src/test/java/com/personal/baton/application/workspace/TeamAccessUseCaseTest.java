package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.identity.port.in.DeactivateAccountUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleHandoffTransitionResult;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.InvitationAcceptedResult;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.TeamPermission;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {BatonApplication.class, TeamAccessUseCaseTest.TimeConfig.class}, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
        "baton.round-automation.poll-interval=PT24H",
        "baton.identity.email-verification.dispatch-interval=PT24H"
})
class TeamAccessUseCaseTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("team_access_test").withUsername("baton").withPassword("password");
    @Autowired WorkspaceLifecycleUseCase lifecycle;
    @Autowired WorkspacePeopleUseCase people;
    @Autowired WorkspaceRecordsUseCase records;
    @Autowired RoundAdministrationUseCase memberships;
    @Autowired TeamAccessUseCase access;
    @Autowired WorkspaceSeasonRepository seasons;
    @Autowired ActiveAccountTeamMembershipVerifier activeMembership;
    @Autowired DeactivateAccountUseCase accountDeactivation;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TeamAccessRepository teamAccess;
    @MockitoSpyBean WorkspaceAccessRepository teams;
    @MockitoBean CurrentAccountProvider current;
    @Autowired IdentityRepository identities;
    @Autowired Clock clock;

    private static final String RECOVERY = "pilot-recovery-key-0000000000000002";
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-09-05T03:00:00Z"));
    @Test
    @DisplayName("팀 계정 전환은 공유 키를 닫고 초대·권한·마지막 관리자·취소·만료를 함께 적용한다")
    void appliesAccountAccessWithoutPromotingLegacyClaims() {
        NOW.set(Instant.parse("2026-09-05T03:00:00Z"));
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("권한 검토 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("박민서", "김준호", "이소라")));
        var team = workspace.teamId(); var season = workspace.seasonId(); var key = workspace.accessKey();
        var members = lifecycle.getWorkspace(team, season, key).members();
        UUID adminMember = members.stream().filter(value -> value.name().equals("박민서")).findFirst().orElseThrow().id();
        UUID viewerMember = members.stream().filter(value -> value.name().equals("김준호")).findFirst().orElseThrow().id();
        UUID inviteMember = members.stream().filter(value -> value.name().equals("이소라")).findFirst().orElseThrow().id();
        var admin = identities.saveAccount(Account.create(UUID.randomUUID(), "민서 계정", clock.instant()));
        var viewer = identities.saveAccount(Account.create(UUID.randomUUID(), "준호 계정", clock.instant()));
        var invited = identities.saveAccount(Account.create(UUID.randomUUID(), "소라 계정", clock.instant()));
        memberships.claimMembership(new ClaimMembershipCommand(admin.getId(), team, season, adminMember, key));
        memberships.claimMembership(new ClaimMembershipCommand(viewer.getId(), team, season, viewerMember, key));
        var otherWorkspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("다른 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("다른 구성원")));
        var otherMember = lifecycle.getWorkspace(otherWorkspace.teamId(), otherWorkspace.seasonId(), otherWorkspace.accessKey()).members().getFirst();
        memberships.claimMembership(new ClaimMembershipCommand(admin.getId(), otherWorkspace.teamId(),
                otherWorkspace.seasonId(), otherMember.id(), otherWorkspace.accessKey()));
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        assertThatThrownBy(() -> access.activate(team, admin.getId(), adminMember, "wrong"))
                .isInstanceOf(WorkspaceRecoveryDeniedException.class);
        assertThat(lifecycle.getWorkspace(team, season, key).team().accountAccessEnabled()).isFalse();
        assertThat(access.getMyTeams(admin.getId()).teams()).isEmpty();
        assertThat(activeMembership.hasActiveMembership(viewer.getId(), team)).isTrue();
        access.activate(team, admin.getId(), adminMember, RECOVERY);
        assertThat(activeMembership.hasActiveMembership(viewer.getId(), team)).isFalse();
        assertThatThrownBy(() -> access.invite(team, admin.getId(), adminMember, TeamPermission.MEMBER))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> access.invite(team, admin.getId(), otherMember.id(), TeamPermission.MEMBER))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> access.changePermission(team, admin.getId(), otherMember.id(), null))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(access.getMyTeams(admin.getId()).teams()).singleElement().satisfies(value -> {
            assertThat(value.teamId()).isEqualTo(team);
            assertThat(value.seasonId()).isEqualTo(season);
            assertThat(value.permission()).isEqualTo(TeamPermission.ADMIN);
        });
        assertThat(lifecycle.getWorkspace(team, season, null).team().permission()).isEqualTo(TeamPermission.ADMIN);
        when(current.currentAccountId()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> lifecycle.getWorkspace(team, season, key)).isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThatThrownBy(() -> lifecycle.getWorkspace(team, season, key)).isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        var invitation = access.invite(team, admin.getId(), viewerMember, TeamPermission.VIEWER);
        assertThat(invitation.token()).matches("[A-Za-z0-9_-]{43}");
        assertThatThrownBy(() -> access.accept(admin.getId(), invitation.token()))
                .isInstanceOf(AccountMembershipConflictException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(invited.getId()));
        assertThatThrownBy(() -> access.accept(invited.getId(), invitation.token()))
                .isInstanceOf(AccountMembershipConflictException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThat(access.getMyTeams(viewer.getId()).teams()).isEmpty();
        assertThat(access.preview(viewer.getId(), invitation.token()).memberId()).isEqualTo(viewerMember);
        access.accept(viewer.getId(), invitation.token());
        assertThat(activeMembership.hasActiveMembership(viewer.getId(), team)).isTrue();
        assertThat(access.getMyTeams(viewer.getId()).teams()).hasSize(1);
        assertThat(access.accept(viewer.getId(), invitation.token()).permission()).isEqualTo(TeamPermission.VIEWER);
        assertThat(access.preview(viewer.getId(), invitation.token()).teamId()).isEqualTo(team);
        assertThat(lifecycle.getWorkspace(team, season, null).team().permission()).isEqualTo(TeamPermission.VIEWER);
        var viewerAccess = access.getAccess(team, viewer.getId(), null);
        assertThat(viewerAccess.memberId()).isEqualTo(viewerMember);
        assertThat(viewerAccess.members()).isEmpty();
        assertThat(viewerAccess.invitations()).isEmpty();
        assertThat(viewerAccess.audit()).isEmpty();
        assertThatThrownBy(() -> people.createMember(team, season, UUID.randomUUID().toString(), null, new CreateMemberCommand("새 구성원")))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> records.createDecision(team, season, UUID.randomUUID().toString(), null,
                new CreateDecisionCommand("결정", "이유", null, viewerMember, List.of())))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        access.changePermission(team, admin.getId(), viewerMember, TeamPermission.MEMBER);
        var role = people.createRole(team, season, UUID.randomUUID().toString(), null,
                new CreateRoleCommand("기록 담당", "이유를 남깁니다", adminMember, viewerMember,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), List.of(), null));
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        records.createDecision(team, season, UUID.randomUUID().toString(), null,
                new CreateDecisionCommand("기록 형식", "짧게 정리합니다", null, viewerMember, List.of(role.id())));
        assertThatThrownBy(() -> lifecycle.updateSeasonEnding(team, season, null, true)).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> memberships.claimMembership(new ClaimMembershipCommand(viewer.getId(), team, season, viewerMember, key)))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        assertThatThrownBy(() -> access.changePermission(team, admin.getId(), adminMember, TeamPermission.VIEWER))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> people.updateMemberDeactivation(team, season, adminMember, null, true))
                .isInstanceOf(DomainValidationException.class);
        var handoff = people.prepareRoleHandoff(team, season, role.id(), UUID.randomUUID().toString(), null,
                new PrepareRoleHandoffCommand(viewerMember, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 12, 31))).handoff();
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThatThrownBy(() -> people.transferRoleHandoff(team, season, role.id(), handoff.id(), null,
                new TransferRoleHandoffCommand(adminMember, true))).isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        people.transferRoleHandoff(team, season, role.id(), handoff.id(), null, new TransferRoleHandoffCommand(adminMember, true));
        assertThatThrownBy(() -> people.acceptRoleHandoff(team, season, role.id(), handoff.id(), null,
                new ConfirmRoleHandoffCommand(viewerMember))).isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThat(people.acceptRoleHandoff(team, season, role.id(), handoff.id(), null,
                new ConfirmRoleHandoffCommand(viewerMember)).role().currentMemberId()).isEqualTo(viewerMember);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        access.changePermission(team, admin.getId(), viewerMember, null);
        assertThat(activeMembership.hasActiveMembership(viewer.getId(), team)).isFalse();
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThat(access.getMyTeams(viewer.getId()).teams()).isEmpty();
        assertThatThrownBy(() -> access.accept(viewer.getId(), invitation.token())).isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        var revoked = access.invite(team, admin.getId(), inviteMember, TeamPermission.MEMBER);
        access.revokeInvitation(team, admin.getId(), revoked.invitation().id());
        when(current.currentAccountId()).thenReturn(Optional.of(invited.getId()));
        assertThatThrownBy(() -> access.accept(invited.getId(), revoked.token())).isInstanceOf(WorkspaceNotFoundException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        var expired = access.invite(team, admin.getId(), inviteMember, TeamPermission.MEMBER);
        NOW.set(expired.invitation().expiresAt());
        when(current.currentAccountId()).thenReturn(Optional.of(invited.getId()));
        assertThatThrownBy(() -> access.accept(invited.getId(), expired.token())).isInstanceOf(WorkspaceNotFoundException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        var fresh = access.invite(team, admin.getId(), inviteMember, TeamPermission.ADMIN);
        assertThat(access.getAccess(team, admin.getId(), null).invitations())
                .filteredOn(value -> value.id().equals(expired.invitation().id()))
                .singleElement().satisfies(value -> assertThat(value.revokedAt()).isEqualTo(clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThatThrownBy(() -> access.accept(viewer.getId(), fresh.token()))
                .isInstanceOf(AccountMembershipConflictException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(invited.getId()));
        assertThat(access.accept(invited.getId(), fresh.token()).memberId()).isEqualTo(inviteMember);
        access.changePermission(team, invited.getId(), adminMember, TeamPermission.VIEWER);
        assertThat(access.getAccess(team, invited.getId(), null).audit()).isNotEmpty();
        lifecycle.updateSeasonEnding(team, season, null, true);
        assertThat(access.getMyTeams(invited.getId()).teams()).singleElement().satisfies(value -> assertThat(value.seasonEnded()).isTrue());
        var next = seasons.saveSeason(Season.create(UUID.randomUUID(), team, "다음 시즌", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1)));
        assertThat(access.accept(invited.getId(), fresh.token()).seasonId()).isEqualTo(next.getId());
        assertThat(access.getMyTeams(invited.getId()).teams()).singleElement().satisfies(value -> {
            assertThat(value.seasonId()).isEqualTo(next.getId());
            assertThat(value.seasonEnded()).isFalse();
        });

        assertThat(lifecycle.getWorkspace(team, season, null).team().permission()).isEqualTo(TeamPermission.ADMIN);
    }
    @Test
    @DisplayName("이전 담당자가 행동할 수 없으면 관리자가 열린 인수인계를 취소하고 실제 수행 계정을 이력에 남긴다")
    void administratorCancelsHandoffWhenFromMemberCannotAct() {
        var fixture = accountTeam("인수인계 복구 팀", "관리자", "활동 종료 담당", "권한 회수 담당", "비활성 계정 담당",
                "열람 담당", "미연결 담당", "활동 담당", "후임");
        UUID successor = fixture.member("후임");
        var successorAccount = join(fixture, successor, TeamPermission.MEMBER);

        // 활동 종료: 준비 중 인수인계는 다른 구성원이 대신 취소할 수 없고 관리자만 취소한다.
        UUID deactivatedMember = fixture.member("활동 종료 담당");
        join(fixture, deactivatedMember, TeamPermission.MEMBER);
        var deactivatedRole = roleOf(fixture, "활동 종료 역할", deactivatedMember, successor);
        var preparing = prepare(fixture, deactivatedRole, successor);
        people.updateMemberDeactivation(fixture.id(), fixture.season(), deactivatedMember, null, true);
        when(current.currentAccountId()).thenReturn(Optional.of(successorAccount));
        assertThatThrownBy(() -> cancel(fixture, deactivatedRole, preparing, deactivatedMember))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        var cancelled = cancel(fixture, deactivatedRole, preparing, deactivatedMember);
        assertThat(cancelled.handoff().status()).isEqualTo(RoleHandoffStatus.CANCELLED);
        assertThat(cancelled.handoff().cancelledByMemberId()).isEqualTo(deactivatedMember);
        assertThat(cancelled.role().nextMemberId()).isNull();
        assertThat(cancel(fixture, deactivatedRole, preparing, deactivatedMember).handoff().cancelledAt())
                .isEqualTo(cancelled.handoff().cancelledAt());
        assertThat(cancellationAudit(fixture)).singleElement().satisfies(audit -> {
            assertThat(audit.actorAccountId()).isEqualTo(fixture.adminAccount());
            assertThat(audit.memberId()).isEqualTo(deactivatedMember);
            assertThat(audit.previousPermission()).isNull();
            assertThat(audit.permission()).isNull();
        });

        // 전달 뒤 이전·다음 담당자 모두 권한을 잃어도 취소할 수 있다.
        UUID revokedMember = fixture.member("권한 회수 담당");
        var revokedAccount = join(fixture, revokedMember, TeamPermission.MEMBER);
        var revokedRole = roleOf(fixture, "권한 회수 역할", revokedMember, successor);
        var transferred = prepare(fixture, revokedRole, successor);
        when(current.currentAccountId()).thenReturn(Optional.of(revokedAccount));
        people.transferRoleHandoff(fixture.id(), fixture.season(), revokedRole, transferred, null,
                new TransferRoleHandoffCommand(revokedMember, true));
        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        access.changePermission(fixture.id(), fixture.adminAccount(), revokedMember, null);
        access.changePermission(fixture.id(), fixture.adminAccount(), successor, null);
        assertThat(cancel(fixture, revokedRole, transferred, revokedMember).handoff().status())
                .isEqualTo(RoleHandoffStatus.CANCELLED);
        access.changePermission(fixture.id(), fixture.adminAccount(), successor, TeamPermission.MEMBER);

        // 계정을 비활성화한 이전 담당자
        UUID closedMember = fixture.member("비활성 계정 담당");
        var closedAccount = join(fixture, closedMember, TeamPermission.MEMBER);
        var closedRole = roleOf(fixture, "비활성 계정 역할", closedMember, successor);
        var closedHandoff = prepare(fixture, closedRole, successor);
        when(current.currentAccountId()).thenReturn(Optional.of(closedAccount));
        accountDeactivation.deactivateAccount(closedAccount);
        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        assertThat(cancel(fixture, closedRole, closedHandoff, closedMember).handoff().status())
                .isEqualTo(RoleHandoffStatus.CANCELLED);

        // 열람자는 전달·취소 권한이 없고, 계정을 연결하지 않은 구성원도 직접 행동할 수 없다.
        UUID viewerMember = fixture.member("열람 담당");
        join(fixture, viewerMember, TeamPermission.VIEWER);
        var viewerRole = roleOf(fixture, "열람 역할", viewerMember, successor);
        var viewerHandoff = prepare(fixture, viewerRole, successor);
        assertThat(cancel(fixture, viewerRole, viewerHandoff, viewerMember).handoff().status())
                .isEqualTo(RoleHandoffStatus.CANCELLED);
        UUID unlinkedMember = fixture.member("미연결 담당");
        var unlinkedRole = roleOf(fixture, "미연결 역할", unlinkedMember, successor);
        var unlinkedHandoff = prepare(fixture, unlinkedRole, successor);
        assertThat(cancel(fixture, unlinkedRole, unlinkedHandoff, unlinkedMember).handoff().status())
                .isEqualTo(RoleHandoffStatus.CANCELLED);
        assertThat(cancellationAudit(fixture)).hasSize(5);

        // 이전 담당자가 행동할 수 있으면 관리자도 대신하지 못하고 본인 취소는 이력을 남기지 않는다.
        UUID activeMember = fixture.member("활동 담당");
        var activeAccount = join(fixture, activeMember, TeamPermission.MEMBER);
        var activeRole = roleOf(fixture, "활동 역할", activeMember, successor);
        var activeHandoff = prepare(fixture, activeRole, successor);
        assertThatThrownBy(() -> cancel(fixture, activeRole, activeHandoff, activeMember))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        when(current.currentAccountId()).thenReturn(Optional.of(activeAccount));
        assertThat(cancel(fixture, activeRole, activeHandoff, activeMember).handoff().status())
                .isEqualTo(RoleHandoffStatus.CANCELLED);
        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        assertThat(cancellationAudit(fixture)).hasSize(5);

        // 열린 인수인계가 사라지면 시즌을 종료할 수 있다.
        assertThat(lifecycle.updateSeasonEnding(fixture.id(), fixture.season(), null, true).endedAt()).isNotNull();
    }
    @Test
    @DisplayName("초대 수락 뒤 이동 시즌은 시작일이 더 늦은 종료 시즌보다 종료되지 않은 시즌을 우선한다")
    void movesToOpenSeasonAfterAcceptingInvitation() {
        var fixture = accountTeam("시즌 이동 팀", "관리자", "신규 구성원");
        var ended = Season.create(UUID.randomUUID(), fixture.id(), "종료한 시즌",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30));
        ended.updateEnding(true, clock.instant());
        seasons.saveSeason(ended);
        var invitation = access.invite(fixture.id(), fixture.adminAccount(), fixture.member("신규 구성원"), TeamPermission.MEMBER);
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), "신규 계정", clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(account.getId()));

        assertThat(access.accept(account.getId(), invitation.token()).seasonId()).isEqualTo(fixture.season());

        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        lifecycle.updateSeasonEnding(fixture.id(), fixture.season(), null, true);
        when(current.currentAccountId()).thenReturn(Optional.of(account.getId()));
        assertThat(access.accept(account.getId(), invitation.token()).seasonId()).isEqualTo(ended.getId());
    }
    @Test
    @DisplayName("같은 토큰의 동시 수락은 앞선 커밋을 잠금 뒤 다시 읽어 현재 권한을 반환한다")
    void returnsCurrentPermissionForConcurrentAcceptOfSameToken() throws Exception {
        var fixture = accountTeam("동시 수락 팀", "관리자", "신규 구성원");
        UUID member = fixture.member("신규 구성원");
        var token = access.invite(fixture.id(), fixture.adminAccount(), member, TeamPermission.MEMBER).token();
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), "신규 계정", clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(account.getId()));

        var waiting = acceptBehindCommittedAccept(fixture.id(), account.getId(), token, token);

        assertThat(waiting.get(10, TimeUnit.SECONDS).permission()).isEqualTo(TeamPermission.MEMBER);
        when(current.currentAccountId()).thenReturn(Optional.of(fixture.adminAccount()));
        assertThat(access.getAccess(fixture.id(), fixture.adminAccount(), null).audit())
                .filteredOn(value -> value.action().equals("INVITATION_ACCEPTED")).hasSize(1);
    }
    @Test
    @DisplayName("한 계정이 다른 구성원 초대를 동시에 수락하면 늦은 요청은 멤버십 충돌로 끝난다")
    void rejectsConcurrentAcceptOfAnotherMemberInvitation() throws Exception {
        var fixture = accountTeam("동시 연결 팀", "관리자", "첫째 구성원", "둘째 구성원");
        var first = access.invite(fixture.id(), fixture.adminAccount(), fixture.member("첫째 구성원"), TeamPermission.MEMBER);
        var second = access.invite(fixture.id(), fixture.adminAccount(), fixture.member("둘째 구성원"), TeamPermission.MEMBER);
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), "신규 계정", clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(account.getId()));

        var waiting = acceptBehindCommittedAccept(fixture.id(), account.getId(), first.token(), second.token());

        assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(AccountMembershipConflictException.class);
        assertThat(access.getMyTeams(account.getId()).teams()).singleElement()
                .satisfies(value -> assertThat(value.memberId()).isEqualTo(fixture.member("첫째 구성원")));

        // 잠금을 우회한 저장도 실제 MySQL 유일 제약이 멤버십 충돌로 변환되는지 확인한다.
        var otherAccount = identities.saveAccount(Account.create(UUID.randomUUID(), "다른 계정", clock.instant()));
        assertThatThrownBy(() -> teamAccess.saveMembership(AccountTeamMembership.create(UUID.randomUUID(),
                account.getId(), fixture.id(), fixture.member("둘째 구성원"), clock.instant())))
                .isInstanceOf(AccountMembershipConflictException.class);
        assertThatThrownBy(() -> teamAccess.saveMembership(AccountTeamMembership.create(UUID.randomUUID(),
                otherAccount.getId(), fixture.id(), fixture.member("첫째 구성원"), clock.instant())))
                .isInstanceOf(AccountMembershipConflictException.class);
    }
    // 뒤따르는 수락이 첫 조회를 마치고 팀 잠금을 기다리는 동안 앞선 수락을 커밋한다.
    private Future<InvitationAcceptedResult> acceptBehindCommittedAccept(UUID teamId, UUID accountId, String committedToken,
            String waitingToken) throws Exception {
        var waitingForTeamLock = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                teams.findTeamByIdForUpdate(teamId);
                doAnswer(invocation -> {
                    waitingForTeamLock.countDown();
                    return invocation.callRealMethod();
                }).when(teams).findTeamByIdForUpdate(teamId);
                var waiting = executor.submit(() -> access.accept(accountId, waitingToken));
                try {
                    assertThat(waitingForTeamLock.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                access.accept(accountId, committedToken);
                return waiting;
            });
        } finally {
            executor.shutdown();
        }
    }
    @Test
    @DisplayName("관리자 초대 목록은 최근 50건만 최신순으로 반환한다")
    void limitsInvitationListToLatestFifty() {
        var fixture = accountTeam("초대 목록 팀", "관리자", "신규 구성원");
        UUID member = fixture.member("신규 구성원");
        var oldest = access.invite(fixture.id(), fixture.adminAccount(), member, TeamPermission.MEMBER).invitation();
        var latest = oldest;
        for (int count = 0; count < 50; count++) {
            NOW.updateAndGet(value -> value.plusSeconds(1));
            latest = access.invite(fixture.id(), fixture.adminAccount(), member, TeamPermission.MEMBER).invitation();
        }

        var invitations = access.getAccess(fixture.id(), fixture.adminAccount(), null).invitations();

        assertThat(invitations).hasSize(50);
        assertThat(invitations.getFirst().id()).isEqualTo(latest.id());
        assertThat(invitations).noneMatch(value -> value.id().equals(oldest.id()));
    }
    private AccountTeam accountTeam(String name, String... memberNames) {
        NOW.set(Instant.parse("2026-09-05T03:00:00Z"));
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand(name, "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of(memberNames)));
        Map<String, UUID> members = new LinkedHashMap<>();
        lifecycle.getWorkspace(workspace.teamId(), workspace.seasonId(), workspace.accessKey()).members()
                .forEach(member -> members.put(member.name(), member.id()));
        var admin = identities.saveAccount(Account.create(UUID.randomUUID(), name + " 관리자", clock.instant()));
        UUID adminMember = members.get(memberNames[0]);
        memberships.claimMembership(new ClaimMembershipCommand(admin.getId(), workspace.teamId(), workspace.seasonId(),
                adminMember, workspace.accessKey()));
        when(current.currentAccountId()).thenReturn(Optional.of(admin.getId()));
        access.activate(workspace.teamId(), admin.getId(), adminMember, RECOVERY);
        return new AccountTeam(workspace.teamId(), workspace.seasonId(), admin.getId(), members);
    }
    private UUID join(AccountTeam team, UUID memberId, TeamPermission permission) {
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), memberId + " 계정", clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(team.adminAccount()));
        var invitation = access.invite(team.id(), team.adminAccount(), memberId, permission);
        when(current.currentAccountId()).thenReturn(Optional.of(account.getId()));
        access.accept(account.getId(), invitation.token());
        when(current.currentAccountId()).thenReturn(Optional.of(team.adminAccount()));
        return account.getId();
    }
    private UUID roleOf(AccountTeam team, String name, UUID currentMember, UUID nextMember) {
        return people.createRole(team.id(), team.season(), UUID.randomUUID().toString(), null,
                new CreateRoleCommand(name, "이유를 남깁니다", currentMember, nextMember,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), List.of(), null)).id();
    }
    private UUID prepare(AccountTeam team, UUID roleId, UUID nextMember) {
        return people.prepareRoleHandoff(team.id(), team.season(), roleId, UUID.randomUUID().toString(), null,
                new PrepareRoleHandoffCommand(nextMember, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31))).handoff().id();
    }
    private RoleHandoffTransitionResult cancel(AccountTeam team, UUID roleId, UUID handoffId, UUID confirmedBy) {
        return people.cancelRoleHandoff(team.id(), team.season(), roleId, handoffId, null, new ConfirmRoleHandoffCommand(confirmedBy));
    }
    private List<TeamAccessUseCase.AccessAuditResult> cancellationAudit(AccountTeam team) {
        return access.getAccess(team.id(), team.adminAccount(), null).audit().stream()
                .filter(value -> value.action().equals("ROLE_HANDOFF_ADMIN_CANCELLED")).toList();
    }
    private record AccountTeam(UUID id, UUID season, UUID adminAccount, Map<String, UUID> members) {
        UUID member(String name) { return members.get(name); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary Clock accessClock() {
            Clock result = mock(Clock.class); when(result.getZone()).thenReturn(ZoneOffset.UTC);
            when(result.instant()).thenAnswer(invocation -> NOW.get()); return result;
        }
    }
}
