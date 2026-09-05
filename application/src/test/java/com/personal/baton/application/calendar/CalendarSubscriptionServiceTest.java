package com.personal.baton.application.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.personal.baton.BatonApplication;
import com.personal.baton.adapter.out.external.calendar.RestClientCalendarClient;
import com.personal.baton.application.calendar.CalendarSubscriptionException.Reason;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Scope;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Status;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient.*;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore.Owner;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.domain.workspace.TeamPermission;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Optional;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BatonApplication.class, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
        "baton.round-automation.poll-interval=PT24H"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CalendarSubscriptionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("baton_calendar_subscriptions").withUsername("baton").withPassword("password");
    @MockitoBean CurrentAccountProvider currentAccount;
    @Autowired TeamAccessUseCase teamAccess;
    private UUID administrator;
    @Autowired WorkspaceLifecycleUseCase workspace;
    @Autowired VerifyWorkspaceAccessUseCase access;
    @Autowired com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase people;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired ActiveAccountTeamMembershipVerifier memberships;
    @Autowired CalendarSubscriptionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestClientCalendarClient.Factory factory;
    private CalendarSubscriptionClient client;
    private CalendarSubscriptionService service;
    private Scope scope;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM calendar_subscriptions");
        var created = workspace.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("구독 검증 팀", "가을 시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), List.of("담당자")));
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, display_name, created_at, updated_at) VALUES (UUID_TO_BIN(?), '구독 계정', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))", account.toString());
        jdbc.update("""
                INSERT INTO account_team_memberships (id,account_id,team_id,member_id,claimed_at)
                SELECT UUID_TO_BIN(?),UUID_TO_BIN(?),team_id,id,CURRENT_TIMESTAMP(6) FROM members WHERE team_id=UUID_TO_BIN(?)
                """, UUID.randomUUID().toString(), account.toString(), created.teamId().toString());
        scope = new Scope(account, created.teamId(), created.seasonId(), created.accessKey());
        client = mock(CalendarSubscriptionClient.class);
        service = service(client, NOW);
        when(client.create(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return credential(call.getArgument(0));
        });
        when(client.rotate(any())).thenAnswer(call -> credential(call.getArgument(0)));
        when(client.revoke(any())).thenReturn(Result.of(Outcome.SUCCESS));
    }

    @Test
    @DisplayName("생성 응답 유실 뒤 같은 ID로 조회하고 명시적인 재발급으로만 주소를 돌려준다")
    void recoversLostCreationWithoutAutomaticRotation() {
        when(client.create(any(), any())).thenReturn(Result.of(Outcome.UNAVAILABLE), Result.of(Outcome.ALREADY_EXISTS));
        assertReason(Reason.UNAVAILABLE, () -> service.create(scope));
        UUID id = store.find(owner()).orElseThrow().subscriptionId();
        when(client.findSubscription(id)).thenReturn(new Result(Outcome.SUCCESS, null, new RemoteStatus(id, scope.seasonId(), false, true)));
        assertThat(service.find(scope).status()).isEqualTo(Status.ACTIVE);
        assertReason(Reason.CREDENTIAL_REQUIRED, () -> service.create(scope));
        verify(client, times(2)).create(id, scope.seasonId());
        verify(client, never()).rotate(any());
        assertThat(service.rotate(scope).subscriptionId()).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT * FROM calendar_subscriptions").values().toString()).doesNotContain("https://", "calendars/v1");
    }

    @Test
    @DisplayName("구독 선점 중에는 중복 발급을 막고 만료된 이전 작업의 완료를 거부한다")
    void fencesExpiredClaim() {
        var previous = store.claim(owner(), true, false, NOW);
        assertReason(Reason.IN_PROGRESS, () -> service.create(scope));
        verifyNoInteractions(client);
        var next = store.claim(owner(), true, false, NOW.plusSeconds(60));
        assertThat(next.subscription().subscriptionId()).isEqualTo(previous.subscription().subscriptionId());
        assertThat(store.release(previous, true)).isFalse();
        assertThat(store.find(owner()).orElseThrow().leaseUntil()).isEqualTo(NOW.plusSeconds(120));
        assertThat(store.release(next, false)).isTrue();
    }

    @Test
    @DisplayName("폐기 응답을 놓쳐도 같은 구독을 다시 폐기하고 이후 발급은 새 ID를 사용한다")
    void retriesDurableRevocation() {
        UUID id = service.create(scope).subscriptionId();
        when(client.revoke(id)).thenReturn(Result.of(Outcome.UNAVAILABLE), Result.of(Outcome.SUCCESS));
        assertReason(Reason.UNAVAILABLE, () -> service.revoke(scope));
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOCATION_PENDING);
        service.revokePending();
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);
        verify(client, times(2)).revoke(id);
        assertThat(service.create(scope).subscriptionId()).isNotEqualTo(id);
    }

    @Test
    @DisplayName("다른 계정은 같은 시즌에서도 기존 구독을 조회하거나 폐기하지 못한다")
    void isolatesOwner() {
        service.create(scope);
        var other = new Scope(UUID.randomUUID(), scope.teamId(), scope.seasonId(), scope.accessKey());
        assertThat(service.find(other).status()).isEqualTo(Status.NOT_CREATED);
        service.revoke(other);
        assertReason(Reason.ACCESS_DENIED, () -> service.create(other));
        verify(client, never()).revoke(any());
    }

    @Test
    @DisplayName("구성원 활동 중지 뒤 주소 발급을 막고 기존 구독을 폐기한다")
    void revokesInactiveOwner() {
        UUID id = service.create(scope).subscriptionId();
        deactivate();
        assertReason(Reason.ACCESS_DENIED, () -> service.rotate(scope));
        service.revokePending();
        verify(client).revoke(id);
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);
    }

    @Test
    @DisplayName("CAL 발급 도중 활동이 중지되면 주소를 반환하지 않고 폐기를 남긴다")
    void checksMembershipBeforeReturningCredential() {
        when(client.create(any(), any())).thenAnswer(call -> { deactivate(); return credential(call.getArgument(0)); });
        assertReason(Reason.ACCESS_DENIED, () -> service.create(scope));
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        service.revokePending();
        assertThat(store.find(owner()).orElseThrow().revoked()).isTrue();
    }

    @Test
    @DisplayName("활동 중지를 취소해도 같은 트랜잭션에 남긴 폐기 요청은 유지한다")
    void persistsDeactivationIntentAcrossReactivation() {
        UUID id = service.create(scope).subscriptionId();
        UUID memberId = UUID.fromString(jdbc.queryForObject("SELECT BIN_TO_UUID(member_id) FROM account_team_memberships WHERE account_id=UUID_TO_BIN(?)", String.class, scope.accountId().toString()));
        people.updateMemberDeactivation(scope.teamId(), scope.seasonId(), memberId, scope.accessKey(), true);
        people.updateMemberDeactivation(scope.teamId(), scope.seasonId(), memberId, scope.accessKey(), false);
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        service.revokePending();
        verify(client).revoke(id);
    }

    @Test
    @DisplayName("활동 중지 원본 트랜잭션이 취소되면 구독 폐기 요청도 취소된다")
    void rollsBackRevocationWithMembershipChange() {
        service.create(scope);
        UUID memberId = UUID.fromString(jdbc.queryForObject("SELECT BIN_TO_UUID(member_id) FROM account_team_memberships WHERE account_id=UUID_TO_BIN(?)", String.class, scope.accountId().toString()));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            people.updateMemberDeactivation(scope.teamId(), scope.seasonId(), memberId, scope.accessKey(), true);
            transaction.setRollbackOnly();
        });
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isFalse();
        assertThat(memberships.hasActiveMembership(scope.accountId(), scope.teamId())).isTrue();
    }

    @Test
    @DisplayName("CAL 발급을 기다리는 동안 시즌이 끝나면 주소를 반환하지 않고 폐기한다")
    void rechecksSeasonAfterExternalCall() {
        when(client.create(any(), any())).thenAnswer(call -> {
            jdbc.update("UPDATE seasons SET ended_at=CURRENT_TIMESTAMP(6) WHERE id=UUID_TO_BIN(?)", scope.seasonId().toString());
            return credential(call.getArgument(0));
        });
        assertThatThrownBy(() -> service.create(scope)).isInstanceOf(com.personal.baton.application.workspace.error.SeasonEndedException.class);
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        service.revoke(scope);
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);
    }

    @Test
    @DisplayName("서버 복원으로 구독 세대가 달라지면 재발급 필요 상태를 반환한다")
    void detectsGenerationMismatch() {
        UUID id = service.create(scope).subscriptionId();
        when(client.findSubscription(id)).thenReturn(new Result(Outcome.SUCCESS, null, new RemoteStatus(id, scope.seasonId(), false, false)));
        assertThat(service.find(scope).status()).isEqualTo(Status.REISSUE_REQUIRED);
        verify(client, never()).rotate(any());
    }

    @Test
    @Tag("calendar-subscription-crossservice")
    @DisplayName("실제 MySQL 소유권과 CAL HTTP 경계에서 발급 응답 유실·재발급·활동 중지 폐기를 검증한다")
    void recoversAndRevokesThroughRealCalendar() throws Exception {
        URI target = URI.create(System.getenv("BATON_CAL_LIVE_BASE_URL"));
        if (!"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                || target.getPort() < 1 || target.getUserInfo() != null || target.getRawQuery() != null
                || target.getRawFragment() != null || !target.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("응답 절단 검증은 격리된 로컬 CAL 주소만 허용합니다");
        }
        String baseUrl = target.toString();
        String testBearer = "calendar-consumer-contract-token-000000000001";
        var real = factory.create(target, testBearer, Duration.ofSeconds(2), Duration.ofSeconds(5));
        var lost = new AtomicReference<Credential>();
        var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try (var forwarding = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            proxy.createContext("/internal/api/v1/subscriptions/", exchange -> {
                try {
                    var request = java.net.http.HttpRequest.newBuilder(URI.create(baseUrl + exchange.getRequestURI()))
                            .header("Authorization", "Bearer " + testBearer)
                            .header("Content-Type", "application/json")
                            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
                    var response = forwarding.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(201);
                    var body = new tools.jackson.databind.ObjectMapper().readTree(response.body());
                    lost.set(new Credential(UUID.fromString(body.get("subscriptionId").asString()), URI.create(body.get("feedUrl").asString())));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    // CAL 커밋 뒤 성공 응답 본문을 보내지 않고 연결을 끊는다.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            var interrupted = factory.create(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort()),
                    testBearer, Duration.ofSeconds(2), Duration.ofSeconds(5));
            service = service(interrupted, NOW);
            assertReason(Reason.UNAVAILABLE, () -> service.create(scope));
            assertThat(lost.get()).isNotNull();
        } finally {
            proxy.stop(0);
        }
        service = service(real, NOW);
        assertThat(service.find(scope).status()).isEqualTo(Status.ACTIVE);
        assertReason(Reason.CREDENTIAL_REQUIRED, () -> service.create(scope));
        assertThat(feedStatus(baseUrl, lost.get().feedUrl())).isEqualTo(200);
        var issued = service.rotate(scope);
        assertThat(issued.subscriptionId()).isEqualTo(lost.get().subscriptionId());
        assertThat(feedStatus(baseUrl, lost.get().feedUrl())).isEqualTo(404);
        assertThat(feedStatus(baseUrl, issued.feedUrl())).isEqualTo(200);
        deactivate();
        service.revokePending();
        assertThat(feedStatus(baseUrl, issued.feedUrl())).isEqualTo(404);
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);

        jdbc.update("UPDATE members SET deactivated_at=NULL WHERE team_id=UUID_TO_BIN(?)", scope.teamId().toString());
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        scope = new Scope(scope.accountId(), scope.teamId(), scope.seasonId(), "");
        var viewerFeed = service.create(scope);
        assertThat(feedStatus(baseUrl, viewerFeed.feedUrl())).isEqualTo(200);
        grant(null);
        grant(TeamPermission.VIEWER);
        service.revokePending();
        assertThat(feedStatus(baseUrl, viewerFeed.feedUrl())).isEqualTo(404);
        var ownerFeed = service.create(scope);
        grant(null);
        service.revoke(scope);
        assertThat(feedStatus(baseUrl, ownerFeed.feedUrl())).isEqualTo(404);
    }

    @Test
    @DisplayName("열람자는 공유 키와 일정 수정 권한 없이 구독을 발급·회전할 수 있다")
    void allowsViewerToManageReadOnlySubscription() {
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        scope = new Scope(scope.accountId(), scope.teamId(), scope.seasonId(), "");
        assertThatThrownBy(() -> access.verifyMutation(scope.teamId(), scope.seasonId(), ""))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        UUID id = service.create(scope).subscriptionId();
        assertThat(service.rotate(scope).subscriptionId()).isEqualTo(id);
        grant(TeamPermission.MEMBER);
        grant(TeamPermission.VIEWER);
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isFalse();
    }

    @Test
    @DisplayName("권한 회수 뒤 즉시 재승인해도 폐기를 유지하고 CAL 장애 뒤 같은 주소를 폐기한다")
    void preservesRevocationAcrossPermissionRestoration() {
        enableAccountAccess();
        grant(TeamPermission.MEMBER);
        UUID id = service.create(scope).subscriptionId();
        grant(null);
        grant(TeamPermission.VIEWER);
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        when(client.revoke(id)).thenReturn(Result.of(Outcome.UNAVAILABLE), Result.of(Outcome.SUCCESS));
        service.revokePending();
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOCATION_PENDING);
        service.revokePending();
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);
        verify(client, times(2)).revoke(id);
        assertThat(service.create(scope).subscriptionId()).isNotEqualTo(id);
    }

    @Test
    @DisplayName("권한 회수가 롤백되면 구독 폐기도 롤백된다")
    void rollsBackPermissionRevocation() {
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        service.create(scope);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            grant(null);
            tx.setRollbackOnly();
        });
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isFalse();
        assertThat(memberships.hasActiveMembership(scope.accountId(), scope.teamId())).isTrue();
    }

    @Test
    @DisplayName("권한과 접근 키가 없어도 본인 구독을 조회·폐기하며 다른 계정과 팀의 구독은 건드리지 않는다")
    void letsOwnerCleanUpAfterAccessRevocation() {
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        UUID id = service.create(scope).subscriptionId();
        grant(null);
        scope = new Scope(scope.accountId(), scope.teamId(), scope.seasonId(), "");
        assertThatThrownBy(() -> service.rotate(scope)).isInstanceOf(WorkspaceAccessDeniedException.class);
        var otherAccount = new Scope(administrator, scope.teamId(), scope.seasonId(), "");
        var otherTeam = new Scope(scope.accountId(), UUID.randomUUID(), scope.seasonId(), "");
        for (Scope wrong : List.of(otherAccount, otherTeam)) {
            assertThat(service.find(wrong).status()).isEqualTo(Status.NOT_CREATED);
            service.revoke(wrong);
        }
        verify(client, never()).revoke(any());
        service = new CalendarSubscriptionService(access, memberships, store, client, Clock.fixed(NOW, ZoneOffset.UTC), false);
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOCATION_PENDING);
        service.revoke(scope);
        assertThat(service.find(scope).status()).isEqualTo(Status.REVOKED);
        verify(client).revoke(id);
    }

    @Test
    @DisplayName("팀을 계정 권한으로 전환하면 승인 전 구성원의 기존 구독도 폐기한다")
    void revokesUnapprovedSubscriptionsWhenTeamSwitchesAccessMode() {
        UUID id = service.create(scope).subscriptionId();
        enableAccountAccess();
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        grant(TeamPermission.VIEWER);
        service.revokePending();
        verify(client).revoke(id);
    }

    @Test
    @DisplayName("CAL 응답을 기다리는 동안 권한을 회수하면 주소를 반환하지 않고 폐기한다")
    void rejectsCredentialAfterPermissionRevocation() {
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        when(client.create(any(), any())).thenAnswer(call -> { grant(null); return credential(call.getArgument(0)); });
        assertThatThrownBy(() -> service.create(scope)).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThat(store.find(owner()).orElseThrow().revocationPending()).isTrue();
        service.revokePending();
        assertThat(store.find(owner()).orElseThrow().revoked()).isTrue();
    }

    @Test
    @DisplayName("기존 데이터 점검은 활동 중이어도 계정 팀 권한이 없는 구독을 폐기한다")
    void detectsMissingPermissionDuringRevocationSweep() {
        enableAccountAccess();
        grant(TeamPermission.VIEWER);
        UUID id = service.create(scope).subscriptionId();
        jdbc.update("UPDATE account_team_memberships SET permission=NULL WHERE account_id=UUID_TO_BIN(?)", scope.accountId().toString());
        service.revokePending();
        verify(client).revoke(id);
    }

    private void enableAccountAccess() {
        var member = people.createMember(scope.teamId(), scope.seasonId(), UUID.randomUUID().toString(),
                scope.accessKey(), new CreateMemberCommand("관리자"));
        administrator = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, display_name, created_at, updated_at) VALUES (UUID_TO_BIN(?), '관리 계정', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))", administrator.toString());
        jdbc.update("""
                INSERT INTO account_team_memberships (id,account_id,team_id,member_id,claimed_at)
                VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),CURRENT_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), administrator.toString(), scope.teamId().toString(), member.id().toString());
        when(currentAccount.currentAccountId()).thenReturn(Optional.of(administrator));
        teamAccess.activate(scope.teamId(), administrator, member.id(), "pilot-recovery-key-0000000000000002");
    }
    private void grant(TeamPermission permission) {
        UUID memberId = UUID.fromString(jdbc.queryForObject("SELECT BIN_TO_UUID(member_id) FROM account_team_memberships WHERE account_id=UUID_TO_BIN(?)", String.class, scope.accountId().toString()));
        when(currentAccount.currentAccountId()).thenReturn(Optional.of(administrator));
        teamAccess.changePermission(scope.teamId(), administrator, memberId, permission);
        when(currentAccount.currentAccountId()).thenReturn(Optional.of(scope.accountId()));
    }

    private int feedStatus(String baseUrl, URI feed) {
        return RestClient.create(baseUrl).get().uri(feed.getRawPath()).exchange((request, response) -> response.getStatusCode().value());
    }
    private void deactivate() { jdbc.update("UPDATE members SET deactivated_at=CURRENT_TIMESTAMP(6) WHERE team_id=UUID_TO_BIN(?)", scope.teamId().toString()); }
    private Owner owner() { return new Owner(scope.accountId(), scope.teamId(), scope.seasonId()); }
    private CalendarSubscriptionService service(CalendarSubscriptionClient remote, Instant now) {
        return new CalendarSubscriptionService(access, memberships, store, remote, Clock.fixed(now, ZoneOffset.UTC), true);
    }
    private Result credential(UUID id) { return new Result(Outcome.SUCCESS, new Credential(id, URI.create("https://cal.b4ton.com/calendars/v1/" + "a".repeat(43) + ".ics")), null); }
    private void assertReason(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CalendarSubscriptionException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
