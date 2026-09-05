package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerifyResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateRoleResourceCommand;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.NotificationReadReceiptPort;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.WorkspaceNotificationKind;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase.ConfigurePreferencesCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.ConfirmRoleHandoffCommand;
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
@SpringBootTest(classes = {BatonApplication.class, WorkspaceNotificationUseCaseTest.TimeConfig.class}, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.identity.email-verification.outbox-encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "baton.round-automation.poll-interval=PT24H",
        "baton.identity.email-verification.dispatch-interval=PT24H"
})
class WorkspaceNotificationUseCaseTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("resource_verification_test").withUsername("baton").withPassword("password");
    @Autowired WorkspaceLifecycleUseCase lifecycle;
    @Autowired WorkspacePeopleUseCase people;
    @Autowired WorkspaceRecordsUseCase records;
    @Autowired RoundAdministrationUseCase memberships;
    @Autowired IdentityRepository identities;
    @Autowired Clock clock;
    @Autowired WorkspaceNotificationUseCase notifications;
    @Autowired WorkspaceOperationsUseCase operations;
    @Autowired NotificationReadReceiptPort receipts;
    @Autowired NotificationPreferencesUseCase preferences;


    private static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-09-05T02:00:00Z"));
    @Test
    @DisplayName("알림은 마감 단계별로 읽음을 보존하고 다른 계정·완료·보관·종료 시즌을 분리한다")
    void persistsReadStateAndReopensOverdueNotification() {
        NOW.set(Instant.parse("2026-09-05T02:00:00Z"));
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("알림 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("박민서")));
        var team = workspace.teamId(); var season = workspace.seasonId(); var key = workspace.accessKey();
        var member = lifecycle.getWorkspace(team, season, key).members().getFirst();
        var role = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("운영 담당", "모임을 준비합니다", member.id(), null, null, null, List.of(), null));
        operations.createRoutine(team, season, UUID.randomUUID().toString(), key,
                new CreateRoutineCommand("운영 자료 준비", RoutinePhase.BEFORE, "12시", role.id(), "자료 준비", 0, LocalTime.NOON));
        var round = operations.createSeasonRound(team, season, UUID.randomUUID().toString(), key,
                new CreateSeasonRoundCommand("첫 모임", LocalDate.of(2026, 9, 5)));
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), "민서", clock.instant()));
        var other = identities.saveAccount(Account.create(UUID.randomUUID(), "다른 계정", clock.instant()));
        memberships.claimMembership(new ClaimMembershipCommand(account.getId(), team, season, member.id(), key));
        assertThat(preferences.get(account.getId()).deadlineLeadHours()).isEqualTo(24);
        NOW.set(Instant.parse("2026-09-04T01:00:00Z"));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        var earlier = preferences.configure(account.getId(), new ConfigurePreferencesCommand(-1, true, true, true, 48));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).hasSize(1);
        assertThat(preferences.get(other.getId()).deadlineLeadHours()).isEqualTo(24);
        assertThatThrownBy(() -> preferences.configure(account.getId(), new ConfigurePreferencesCommand(-1, false, true, true, 24)))
                .isInstanceOf(WorkspaceContentConflictException.class);
        NOW.set(Instant.parse("2026-09-05T02:00:00Z"));
        var initial = notifications.getInbox(team, season, key, account.getId()).notifications().getFirst();
        assertThat(initial.kind()).isEqualTo(WorkspaceNotificationKind.DEADLINE_SOON);
        assertThat(initial.read()).isFalse();
        assertThat(notifications.markRead(team, season, key, account.getId(), initial.id()).notifications().getFirst().read()).isTrue();
        assertThat(notifications.markRead(team, season, key, account.getId(), initial.id()).notifications().getFirst().read()).isTrue();
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications().getFirst().read()).isTrue();
        assertThat(receipts.findRead(other.getId(), List.of(initial.id()))).isEmpty();
        assertThatThrownBy(() -> notifications.markRead(team, season, key, other.getId(), initial.id()))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        var hidden = preferences.configure(account.getId(), new ConfigurePreferencesCommand(earlier.version(), false, true, true, 1));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        var enabled = preferences.configure(account.getId(), new ConfigurePreferencesCommand(hidden.version(), true, true, true, 1));
        NOW.set(Instant.parse("2026-09-05T02:00:00Z").minusNanos(1));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        NOW.set(Instant.parse("2026-09-05T02:00:00Z"));
        var visibleAgain = notifications.getInbox(team, season, key, account.getId()).notifications().getFirst();
        assertThat(visibleAgain.id()).isEqualTo(initial.id()); assertThat(visibleAgain.read()).isTrue();
        NOW.set(Instant.parse("2026-09-05T03:00:00Z"));
        var overdue = notifications.getInbox(team, season, key, account.getId()).notifications().getFirst();
        assertThat(overdue.id()).isNotEqualTo(initial.id());
        assertThat(overdue.kind()).isEqualTo(WorkspaceNotificationKind.OVERDUE);
        assertThat(overdue.read()).isFalse();
        operations.updateRoutineExecutionCompletion(team, season, round.id(), overdue.sourceId(), key, true);
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        operations.updateRoutineExecutionCompletion(team, season, round.id(), overdue.sourceId(), key, false);
        operations.updateSeasonRoundArchive(team, season, round.id(), key, true);
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        operations.updateSeasonRoundArchive(team, season, round.id(), key, false);
        var outgoing = people.createMember(team, season, UUID.randomUUID().toString(), key, new CreateMemberCommand("김준호"));
        var handedRole = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("후임 역할", "운영 인수", outgoing.id(), member.id(),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), List.of(), null));
        var prepared = people.prepareRoleHandoff(team, season, handedRole.id(), UUID.randomUUID().toString(), key,
                new PrepareRoleHandoffCommand(member.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)));
        people.transferRoleHandoff(team, season, handedRole.id(), prepared.handoff().id(), key,
                new TransferRoleHandoffCommand(outgoing.id(), true));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications())
                .anyMatch(value -> value.kind() == WorkspaceNotificationKind.HANDOFF_REQUEST
                        && value.sourceId().equals(prepared.handoff().id()) && value.roundId() == null);
        var allOff = preferences.configure(account.getId(), new ConfigurePreferencesCommand(enabled.version(), false, false, false, 1));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
        preferences.configure(account.getId(), new ConfigurePreferencesCommand(allOff.version(), true, true, true, 1));
        people.cancelRoleHandoff(team, season, handedRole.id(), prepared.handoff().id(), key,
                new ConfirmRoleHandoffCommand(outgoing.id()));
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications())
                .noneMatch(value -> value.kind() == WorkspaceNotificationKind.HANDOFF_REQUEST);
        lifecycle.updateSeasonEnding(team, season, key, true);
        assertThat(notifications.getInbox(team, season, key, account.getId()).notifications()).isEmpty();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary Clock notificationClock() {
            Clock result = mock(Clock.class);
            when(result.getZone()).thenReturn(ZoneOffset.UTC);
            when(result.instant()).thenAnswer(invocation -> NOW.get());
            return result;
        }
    }
}
