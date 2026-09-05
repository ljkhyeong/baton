package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.ConfirmRoleHandoffCommand;
import com.personal.baton.domain.identity.Account;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.domain.workspace.TeamPermission;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
        "baton.identity.email-verification.outbox-encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
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
        access.activate(team, admin.getId(), adminMember, RECOVERY);
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
        when(current.currentAccountId()).thenReturn(Optional.of(viewer.getId()));
        assertThat(access.getMyTeams(viewer.getId()).teams()).isEmpty();
        assertThat(access.preview(viewer.getId(), invitation.token()).memberId()).isEqualTo(viewerMember);
        access.accept(viewer.getId(), invitation.token());
        assertThat(access.getMyTeams(viewer.getId()).teams()).hasSize(1);
        assertThat(access.accept(viewer.getId(), invitation.token()).permission()).isEqualTo(TeamPermission.VIEWER);
        assertThat(access.preview(viewer.getId(), invitation.token()).teamId()).isEqualTo(team);
        assertThat(lifecycle.getWorkspace(team, season, null).team().permission()).isEqualTo(TeamPermission.VIEWER);
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
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary Clock accessClock() {
            Clock result = mock(Clock.class); when(result.getZone()).thenReturn(ZoneOffset.UTC);
            when(result.instant()).thenAnswer(invocation -> NOW.get()); return result;
        }
    }
}
