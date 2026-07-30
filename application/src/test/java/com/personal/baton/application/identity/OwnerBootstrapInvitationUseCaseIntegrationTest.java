package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                OwnerBootstrapInvitationUseCaseIntegrationTest.FixedClockConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key",
                "baton.workspace.recovery-key=pilot-recovery-key",
                "baton.identity.bootstrap-key=operator-bootstrap-key-0000000000000001",
                "baton.identity.invitation-hmac-secret=invitation-hmac-secret-000000000000001",
                "baton.identity.bootstrap-invitation-ttl=PT1H"
        }
)
class OwnerBootstrapInvitationUseCaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String BOOTSTRAP_KEY =
            "operator-bootstrap-key-0000000000000001";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_owner_bootstrap")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OwnerBootstrapInvitationUseCase invitationUseCase;

    @Autowired
    private OidcIdentityUseCase oidcIdentityUseCase;

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("실제 MySQL에서 초대 발급을 동일 토큰으로 재생하고 같은 계정의 OWNER 수락만 재생한다")
    @Test
    void issuesAndAcceptsOwnerBootstrapIdempotentlyWithoutRawSecretStorage() {
        CreatedWorkspaceResult workspace = createWorkspace(
                "owner-bootstrap-integration-issue-0001",
                "OWNER bootstrap 통합 팀",
                List.of("박민서")
        );
        MemberResult member = memberNamed(workspace, "박민서");
        UUID accountId = resolveAccount("owner-bootstrap-primary-subject");
        String idempotencyKey = "30000000-0000-4000-8000-000000000001";

        IssuedOwnerBootstrapInvitation first = invitationUseCase.issue(
                BOOTSTRAP_KEY,
                idempotencyKey,
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), member.id())
        );
        IssuedOwnerBootstrapInvitation replay = invitationUseCase.issue(
                BOOTSTRAP_KEY,
                idempotencyKey,
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), member.id())
        );

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.token()).isEqualTo(first.token());
        assertThat(replay.invitationId()).isEqualTo(first.invitationId());
        assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertOnlyHashesAreStored(first, idempotencyKey);

        AcceptedOwnerBootstrapInvitation accepted = invitationUseCase.accept(
                first.token(),
                new AuthenticatedAccount(accountId)
        );
        AcceptedOwnerBootstrapInvitation acceptedReplay = invitationUseCase.accept(
                first.token(),
                new AuthenticatedAccount(accountId)
        );

        assertThat(acceptedReplay).isEqualTo(accepted);
        assertThat(accepted.role().name()).isEqualTo("OWNER");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT role, BIN_TO_UUID(user_account_id) AS account_id "
                        + "FROM member_identity_bindings WHERE member_id = UUID_TO_BIN(?)",
                member.id().toString()
        )).containsEntry("role", "OWNER")
                .containsEntry("account_id", accountId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM owner_bootstrap_invitations "
                        + "WHERE id = UUID_TO_BIN(?) AND consumed_at IS NOT NULL "
                        + "AND consumed_by_account_id = UUID_TO_BIN(?)",
                Integer.class,
                first.invitationId().toString(),
                accountId.toString()
        )).isEqualTo(1);

        UUID otherAccountId = resolveAccount("owner-bootstrap-other-subject");
        assertCode(
                () -> invitationUseCase.accept(
                        first.token(),
                        new AuthenticatedAccount(otherAccountId)
                ),
                "BOOTSTRAP_INVITATION_USED"
        );
    }

    @DisplayName("같은 bootstrap 토큰을 두 계정이 동시에 claim하면 한 계정만 소비와 OWNER 결속에 성공한다")
    @Test
    void serializesConcurrentClaimsForSameInvitation() throws Exception {
        CreatedWorkspaceResult workspace = createWorkspace(
                "owner-bootstrap-concurrent-token-0001",
                "동일 초대 경합 팀",
                List.of("김준호")
        );
        MemberResult member = memberNamed(workspace, "김준호");
        IssuedOwnerBootstrapInvitation invitation = invitationUseCase.issue(
                BOOTSTRAP_KEY,
                "30000000-0000-4000-8000-000000000002",
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), member.id())
        );
        UUID firstAccountId = resolveAccount("concurrent-token-first");
        UUID secondAccountId = resolveAccount("concurrent-token-second");

        List<Object> outcomes = runConcurrently(
                () -> acceptOutcome(invitation.token(), firstAccountId),
                () -> acceptOutcome(invitation.token(), secondAccountId)
        );

        assertThat(outcomes)
                .filteredOn(AcceptedOwnerBootstrapInvitation.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(IdentityOperationException.class::isInstance)
                .map(IdentityOperationException.class::cast)
                .extracting(IdentityOperationException::getCode)
                .containsExactly("BOOTSTRAP_INVITATION_USED");
        AcceptedOwnerBootstrapInvitation winner = outcomes.stream()
                .filter(AcceptedOwnerBootstrapInvitation.class::isInstance)
                .map(AcceptedOwnerBootstrapInvitation.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT BIN_TO_UUID(consumed_by_account_id) AS account_id, consumed_at "
                        + "FROM owner_bootstrap_invitations WHERE id = UUID_TO_BIN(?)",
                invitation.invitationId().toString()
        )).containsEntry("account_id", winner.accountId().toString())
                .extractingByKey("consumed_at")
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_identity_bindings "
                        + "WHERE team_id = UUID_TO_BIN(?) AND role = 'OWNER'",
                Integer.class,
                workspace.teamId().toString()
        )).isEqualTo(1);
    }

    @DisplayName("동일한 멱등 bootstrap 발급이 겹치면 한 요청만 생성되고 경합 요청은 같은 토큰을 재생한다")
    @Test
    void marksConcurrentIssueWinnerAndReplay() throws Exception {
        CreatedWorkspaceResult workspace = createWorkspace(
                "owner-bootstrap-concurrent-issue-001",
                "초대 발급 경합 팀",
                List.of("발급 대상")
        );
        MemberResult member = memberNamed(workspace, "발급 대상");
        String idempotencyKey = "30000000-0000-4000-8000-000000000005";

        List<Object> outcomes = runConcurrently(
                () -> invitationUseCase.issue(
                        BOOTSTRAP_KEY,
                        idempotencyKey,
                        new IssueOwnerBootstrapInvitationCommand(
                                workspace.teamId(),
                                member.id()
                        )
                ),
                () -> invitationUseCase.issue(
                        BOOTSTRAP_KEY,
                        idempotencyKey,
                        new IssueOwnerBootstrapInvitationCommand(
                                workspace.teamId(),
                                member.id()
                        )
                )
        );

        List<IssuedOwnerBootstrapInvitation> invitations = outcomes.stream()
                .map(IssuedOwnerBootstrapInvitation.class::cast)
                .toList();
        assertThat(invitations)
                .extracting(IssuedOwnerBootstrapInvitation::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(invitations)
                .extracting(IssuedOwnerBootstrapInvitation::invitationId)
                .containsOnly(invitations.getFirst().invitationId());
        assertThat(invitations)
                .extracting(IssuedOwnerBootstrapInvitation::token)
                .containsOnly(invitations.getFirst().token());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM owner_bootstrap_invitations "
                        + "WHERE team_id = UUID_TO_BIN(?) AND member_id = UUID_TO_BIN(?)",
                Integer.class,
                workspace.teamId().toString(),
                member.id().toString()
        )).isEqualTo(1);
    }

    @DisplayName("서로 다른 bootstrap 초대가 동시에 OWNER를 claim해도 팀당 한 결속만 커밋한다")
    @Test
    void enforcesSingleOwnerAcrossConcurrentInvitations() throws Exception {
        CreatedWorkspaceResult workspace = createWorkspace(
                "owner-bootstrap-concurrent-owner-001",
                "OWNER 유일성 경합 팀",
                List.of("첫 번째 후보", "두 번째 후보")
        );
        MemberResult firstMember = memberNamed(workspace, "첫 번째 후보");
        MemberResult secondMember = memberNamed(workspace, "두 번째 후보");
        IssuedOwnerBootstrapInvitation firstInvitation = invitationUseCase.issue(
                BOOTSTRAP_KEY,
                "30000000-0000-4000-8000-000000000003",
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), firstMember.id())
        );
        IssuedOwnerBootstrapInvitation secondInvitation = invitationUseCase.issue(
                BOOTSTRAP_KEY,
                "30000000-0000-4000-8000-000000000004",
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), secondMember.id())
        );
        UUID firstAccountId = resolveAccount("concurrent-owner-first");
        UUID secondAccountId = resolveAccount("concurrent-owner-second");

        List<Object> outcomes = runConcurrently(
                () -> acceptOutcome(firstInvitation.token(), firstAccountId),
                () -> acceptOutcome(secondInvitation.token(), secondAccountId)
        );

        assertThat(outcomes)
                .filteredOn(AcceptedOwnerBootstrapInvitation.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(IdentityOperationException.class::isInstance)
                .map(IdentityOperationException.class::cast)
                .extracting(IdentityOperationException::getCode)
                .containsExactly("BOOTSTRAP_OWNER_EXISTS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_identity_bindings "
                        + "WHERE team_id = UUID_TO_BIN(?) AND role = 'OWNER'",
                Integer.class,
                workspace.teamId().toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM owner_bootstrap_invitations "
                        + "WHERE id IN (UUID_TO_BIN(?), UUID_TO_BIN(?)) "
                        + "AND consumed_at IS NOT NULL",
                Integer.class,
                firstInvitation.invitationId().toString(),
                secondInvitation.invitationId().toString()
        )).isEqualTo(1);
    }

    private CreatedWorkspaceResult createWorkspace(
            String idempotencyKey,
            String teamName,
            List<String> memberNames
    ) {
        return workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        teamName,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        memberNames
                )
        );
    }

    private MemberResult memberNamed(CreatedWorkspaceResult workspace, String name) {
        return workspaceUseCase.getWorkspace(
                        workspace.teamId(),
                        workspace.seasonId(),
                        workspace.accessKey()
                ).members().stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private UUID resolveAccount(String subject) {
        return oidcIdentityUseCase.resolveAccount(new VerifiedOidcIdentity(
                "https://login.example.com/oidc",
                subject
        )).accountId();
    }

    private void assertOnlyHashesAreStored(
            IssuedOwnerBootstrapInvitation invitation,
            String idempotencyKey
    ) {
        java.util.Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT idempotency_key_hash, token_hash "
                        + "FROM owner_bootstrap_invitations WHERE id = UUID_TO_BIN(?)",
                invitation.invitationId().toString()
        );
        assertThat(stored.get("idempotency_key_hash").toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(idempotencyKey);
        assertThat(stored.get("token_hash").toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(invitation.token());
    }

    private Object acceptOutcome(String token, UUID accountId) {
        try {
            return invitationUseCase.accept(token, new AuthenticatedAccount(accountId));
        } catch (IdentityOperationException exception) {
            return exception;
        }
    }

    private List<Object> runConcurrently(
            java.util.concurrent.Callable<Object> firstTask,
            java.util.concurrent.Callable<Object> secondTask
    ) throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return firstTask.call();
            });
            Future<Object> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return secondTask.call();
            });
            start.await(10, TimeUnit.SECONDS);
            return List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(
                IdentityOperationException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
