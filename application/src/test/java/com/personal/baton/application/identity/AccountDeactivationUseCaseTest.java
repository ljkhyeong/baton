package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.application.identity.error.AccountDeactivationBlockedException;
import com.personal.baton.application.identity.error.PasswordResetException;
import com.personal.baton.application.identity.port.in.DeactivateAccountUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.LocalCredential;
import com.personal.baton.domain.workspace.TeamPermission;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BatonApplication.class, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
        "baton.identity.email-verification.outbox-encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "baton.round-automation.poll-interval=PT24H", "baton.identity.email-verification.dispatch-interval=PT24H"
})
class AccountDeactivationUseCaseTest {
    @Container @ServiceConnection static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("account_deactivation_test").withUsername("baton").withPassword("password");
    @Autowired IdentityRepository identities;
    @Autowired DeactivateAccountUseCase deactivate;
    @Autowired ValidateAccountSessionUseCase sessions;
    @Autowired LoadLocalCredentialUseCase credentials;
    @Autowired ResolveExternalLoginUseCase external;
    @Autowired PasswordResetUseCase passwords;
    @Autowired WorkspaceLifecycleUseCase lifecycle;
    @Autowired WorkspaceRecordsUseCase records;
    @Autowired WorkspacePeopleUseCase people;
    @Autowired RoundAdministrationUseCase memberships;
    @Autowired TeamAccessUseCase access;
    @Autowired TeamAccessRepository accessRepository;
    @Autowired ActiveAccountTeamMembershipVerifier activeMembership;
    @Autowired CalendarSubscriptionStore calendars;
    @Autowired Clock clock;
    @MockitoBean CurrentAccountProvider current;
    private final ThreadLocal<UUID> actor = new ThreadLocal<>();
    private static final String CREATION = "pilot-operator-key-0000000000000001";
    private static final String RECOVERY = "pilot-recovery-key-0000000000000002";

    @Test @DisplayName("비활성화는 마지막 관리자를 보호하고 기록을 보존하며 로그인·권한·개인 구독을 정리한다")
    void deactivatesWithoutLosingTeamRecords() {
        when(current.currentAccountId()).thenAnswer(ignored -> Optional.ofNullable(actor.get()));
        var fixture = createTeam();
        var account = fixture.first(); var other = fixture.second(); var now = clock.instant();
        String email = UUID.randomUUID() + "@example.com";
        var identity = AccountIdentity.createLocal(UUID.randomUUID(), account.getId(), email, now);
        identity.verifyLocalEmail(); identities.saveIdentity(identity);
        identities.saveLocalCredential(LocalCredential.create(identity.getId(), "{noop}retained-password", now));
        String subject = UUID.randomUUID().toString();
        identities.saveIdentity(AccountIdentity.createExternal(UUID.randomUUID(), account.getId(), IdentityProvider.GOOGLE, subject, email, true, now));
        String resetToken = UUID.randomUUID().toString();
        identities.saveEmailVerificationChallenge(EmailVerificationChallenge.createForPasswordReset(UUID.randomUUID(), identity.getId(),
                VerificationTokenHash.passwordResetHash(resetToken), now, now.plusSeconds(1800)));
        var owner = new CalendarSubscriptionStore.Owner(account.getId(), fixture.team(), fixture.season());
        var claim = calendars.claim(owner, true, false, now); calendars.release(claim, false);
        actor.set(account.getId());
        var role = people.createRole(fixture.team(), fixture.season(), UUID.randomUUID().toString(), "",
                new CreateRoleCommand("기록 담당", "결정을 보존합니다", fixture.firstMember(), null,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of(), null));
        var decision = records.createDecision(fixture.team(), fixture.season(), UUID.randomUUID().toString(), "",
                new CreateDecisionCommand("기존 결정을 보존한다", "계정 상태와 팀 기록은 별도로 관리한다", null, fixture.firstMember(), List.of(role.id())));
        assertThatThrownBy(() -> deactivate.deactivateAccount(account.getId())).isInstanceOf(AccountDeactivationBlockedException.class);
        assertThat(identities.findAccountById(account.getId()).orElseThrow().isActive()).isTrue();
        assertThat(calendars.find(owner).orElseThrow().revocationPending()).isFalse();
        String token = access.invite(fixture.team(), account.getId(), fixture.secondMember(), TeamPermission.ADMIN).token();
        actor.set(other.getId()); access.accept(other.getId(), token);
        actor.set(account.getId()); deactivate.deactivateAccount(account.getId());
        var disabled = identities.findAccountById(account.getId()).orElseThrow();
        assertThat(disabled.isActive()).isFalse();
        assertThat(disabled.getSessionVersion()).isEqualTo(account.getSessionVersion() + 1);
        assertThat(sessions.isAccountSessionCurrent(account.getId(), account.getSessionVersion())).isFalse();
        assertThat(sessions.isAccountSessionCurrent(account.getId(), disabled.getSessionVersion())).isFalse();
        assertThat(credentials.loadLocalCredential(email)).isEmpty();
        assertThat(identities.findLocalCredentialByIdentityId(identity.getId())).isPresent();
        assertThatThrownBy(() -> external.resolveExternalLogin(new ResolveExternalLoginUseCase.ExternalLoginCommand(
                IdentityProvider.GOOGLE, subject, email, true, "기존 사용자"))).isInstanceOf(AccountDeactivatedException.class);
        assertThatThrownBy(() -> passwords.resetPassword(new PasswordResetUseCase.ResetPasswordCommand(resetToken, "new-password-12345")))
                .isInstanceOf(PasswordResetException.class);
        assertThat(activeMembership.hasActiveMembership(account.getId(), fixture.team())).isFalse();
        assertThat(calendars.find(owner).orElseThrow().revocationPending()).isTrue();
        assertThat(calendars.pendingRevocations(clock.instant())).contains(owner);
        assertThat(accessRepository.findMemberships(fixture.team()).stream().filter(row -> row.getAccountId().equals(account.getId())).findFirst().orElseThrow().getPermission()).isNull();
        actor.set(other.getId());
        assertThat(lifecycle.getWorkspace(fixture.team(), fixture.season(), "").decisions()).anyMatch(row -> row.id().equals(decision.id()) && row.authorMemberId().equals(fixture.firstMember()));
        assertThatThrownBy(() -> access.changePermission(fixture.team(), other.getId(), fixture.firstMember(), TeamPermission.ADMIN)).isInstanceOf(AccountDeactivatedException.class);
    }

    @Test @DisplayName("두 관리자가 동시에 비활성화해도 한 명의 활성 관리자를 남긴다")
    void concurrentDeactivationsKeepAnAdministrator() throws Exception {
        when(current.currentAccountId()).thenAnswer(ignored -> Optional.ofNullable(actor.get()));
        var fixture = createTeam();
        actor.set(fixture.first().getId());
        String token = access.invite(fixture.team(), fixture.first().getId(), fixture.secondMember(), TeamPermission.ADMIN).token();
        actor.set(fixture.second().getId()); access.accept(fixture.second().getId(), token);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> commands = List.of(() -> tryDeactivate(fixture.first().getId()), () -> tryDeactivate(fixture.second().getId()));
            var results = executor.invokeAll(commands);
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(accessRepository.findMemberships(fixture.team()).stream().filter(row -> row.getPermission() == TeamPermission.ADMIN)).hasSize(1);
    }

    private boolean tryDeactivate(UUID accountId) {
        actor.set(accountId);
        try { deactivate.deactivateAccount(accountId); return true; }
        catch (AccountDeactivationBlockedException exception) { return false; }
        finally { actor.remove(); }
    }

    private Fixture createTeam() {
        actor.remove();
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), CREATION,
                new CreateWorkspaceCommand("계정 정리 " + UUID.randomUUID(), "검증 시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("담당자", "후임자")));
        var members = lifecycle.getWorkspace(workspace.teamId(), workspace.seasonId(), workspace.accessKey()).members();
        var first = identities.saveAccount(Account.create(UUID.randomUUID(), "담당 계정", clock.instant()));
        var second = identities.saveAccount(Account.create(UUID.randomUUID(), "후임 계정", clock.instant()));
        UUID firstMember = members.stream().filter(value -> value.name().equals("담당자")).findFirst().orElseThrow().id();
        UUID secondMember = members.stream().filter(value -> value.name().equals("후임자")).findFirst().orElseThrow().id();
        memberships.claimMembership(new RoundAdministrationUseCase.ClaimMembershipCommand(first.getId(), workspace.teamId(), workspace.seasonId(), firstMember, workspace.accessKey()));
        actor.set(first.getId()); access.activate(workspace.teamId(), first.getId(), firstMember, RECOVERY);
        return new Fixture(workspace.teamId(), workspace.seasonId(), first, second, firstMember, secondMember);
    }
    private record Fixture(UUID team, UUID season, Account first, Account second, UUID firstMember, UUID secondMember) {}
}
