package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.AcceptedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssueMemberInvitationCommand;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssuedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.RevokedMemberInvitation;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                MemberInvitationUseCaseIntegrationTest.FixedClockConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key",
                "baton.workspace.recovery-key=pilot-recovery-key",
                "baton.identity.bootstrap-key=operator-bootstrap-key-0000000000000001",
                "baton.identity.invitation-hmac-secret=invitation-hmac-secret-000000000000001",
                "baton.identity.bootstrap-invitation-ttl=PT1H",
                "baton.identity.member-invitation-ttl=PT24H"
        }
)
class MemberInvitationUseCaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String BOOTSTRAP_KEY =
            "operator-bootstrap-key-0000000000000001";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_member_invitation")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MemberInvitationUseCase memberInvitationUseCase;

    @Autowired
    private OwnerBootstrapInvitationUseCase bootstrapInvitationUseCase;

    @Autowired
    private OidcIdentityUseCase oidcIdentityUseCase;

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("실제 MySQL에서 OWNER 발급·미리보기·MEMBER 수락과 같은 계정 재생을 완결한다")
    @Test
    void completesOwnerIssuedMemberInvitationFlowWithoutRawSecretStorage() {
        Fixture fixture = createOwnedWorkspace(
                "member-invitation-flow-00000000000001",
                "구성원 초대 통합 팀",
                List.of("OWNER", "초대 대상")
        );
        MemberResult target = memberNamed(fixture.workspace(), "초대 대상");
        UUID inviteeAccountId = resolveAccount("member-invitation-flow-invitee");
        String idempotencyKey = "40000000-0000-4000-8000-000000000001";

        IssuedMemberInvitation issued = memberInvitationUseCase.issue(
                account(fixture.ownerAccountId()),
                idempotencyKey,
                new IssueMemberInvitationCommand(fixture.workspace().teamId(), target.id())
        );
        IssuedMemberInvitation replay = memberInvitationUseCase.issue(
                account(fixture.ownerAccountId()),
                idempotencyKey,
                new IssueMemberInvitationCommand(fixture.workspace().teamId(), target.id())
        );

        assertThat(issued.token()).startsWith("mi1_").hasSize(47);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 60 * 60));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.token()).isEqualTo(issued.token());
        assertThat(memberInvitationUseCase.listOpen(
                fixture.workspace().teamId(),
                account(fixture.ownerAccountId())
        )).extracting(MemberInvitationUseCase.OpenMemberInvitation::invitationId)
                .containsExactly(issued.invitationId());

        var preview = memberInvitationUseCase.preview(
                issued.token(),
                account(inviteeAccountId)
        );
        assertThat(preview.teamName()).isEqualTo("구성원 초대 통합 팀");
        assertThat(preview.memberName()).isEqualTo("초대 대상");
        assertThat(preview.alreadyAccepted()).isFalse();

        AcceptedMemberInvitation accepted = memberInvitationUseCase.accept(
                issued.token(),
                account(inviteeAccountId)
        );
        AcceptedMemberInvitation acceptedReplay = memberInvitationUseCase.accept(
                issued.token(),
                account(inviteeAccountId)
        );
        assertThat(accepted.role()).isEqualTo(
                com.personal.baton.domain.identity.MemberIdentityRole.MEMBER
        );
        assertThat(acceptedReplay).isEqualTo(accepted);
        assertThat(memberInvitationUseCase.listOpen(
                fixture.workspace().teamId(),
                account(fixture.ownerAccountId())
        )).isEmpty();

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT idempotency_key_hash, token_hash, "
                        + "BIN_TO_UUID(consumed_by_account_id) AS consumed_by "
                        + "FROM member_invitations WHERE id = UUID_TO_BIN(?)",
                issued.invitationId().toString()
        );
        assertThat(stored.get("idempotency_key_hash").toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(idempotencyKey);
        assertThat(stored.get("token_hash").toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(issued.token());
        assertThat(stored).containsEntry("consumed_by", inviteeAccountId.toString());
    }

    @DisplayName("동일한 멱등 발급이 겹치면 실제 MySQL에 한 행과 한 토큰만 만든다")
    @Test
    void serializesConcurrentIdempotentIssue() throws Exception {
        Fixture fixture = createOwnedWorkspace(
                "member-invitation-concurrent-idem-001",
                "멱등 발급 경합 팀",
                List.of("OWNER", "초대 대상")
        );
        MemberResult target = memberNamed(fixture.workspace(), "초대 대상");
        String idempotencyKey = "40000000-0000-4000-8000-000000000002";

        List<Object> outcomes = runConcurrently(
                () -> issue(fixture, target, idempotencyKey),
                () -> issue(fixture, target, idempotencyKey)
        );

        List<IssuedMemberInvitation> invitations = outcomes.stream()
                .map(IssuedMemberInvitation.class::cast)
                .toList();
        assertThat(invitations)
                .extracting(IssuedMemberInvitation::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(invitations)
                .extracting(IssuedMemberInvitation::invitationId)
                .containsOnly(invitations.getFirst().invitationId());
        assertThat(invitations)
                .extracting(IssuedMemberInvitation::token)
                .containsOnly(invitations.getFirst().token());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_invitations "
                        + "WHERE team_id = UUID_TO_BIN(?) AND member_id = UUID_TO_BIN(?)",
                Integer.class,
                fixture.workspace().teamId().toString(),
                target.id().toString()
        )).isEqualTo(1);
    }

    @DisplayName("같은 대상의 다른 발급 키가 겹쳐도 열린 초대는 하나만 남긴다")
    @Test
    void serializesConcurrentIssueForSameTarget() throws Exception {
        Fixture fixture = createOwnedWorkspace(
                "member-invitation-concurrent-target-01",
                "대상 발급 경합 팀",
                List.of("OWNER", "초대 대상")
        );
        MemberResult target = memberNamed(fixture.workspace(), "초대 대상");

        List<Object> outcomes = runConcurrently(
                () -> issueOutcome(
                        fixture,
                        target,
                        "40000000-0000-4000-8000-000000000003"
                ),
                () -> issueOutcome(
                        fixture,
                        target,
                        "40000000-0000-4000-8000-000000000004"
                )
        );

        assertThat(outcomes)
                .filteredOn(IssuedMemberInvitation.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(IdentityOperationException.class::isInstance)
                .map(IdentityOperationException.class::cast)
                .extracting(IdentityOperationException::getCode)
                .containsExactly("MEMBER_INVITATION_TARGET_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_invitations "
                        + "WHERE team_id = UUID_TO_BIN(?) AND member_id = UUID_TO_BIN(?) "
                        + "AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
                Integer.class,
                fixture.workspace().teamId().toString(),
                target.id().toString(),
                NOW
        )).isEqualTo(1);
    }

    @DisplayName("초대 수락과 OWNER 폐기가 겹치면 실제 MySQL에 하나의 terminal 상태만 커밋한다")
    @Test
    void serializesConcurrentAcceptAndRevoke() throws Exception {
        Fixture fixture = createOwnedWorkspace(
                "member-invitation-accept-revoke-0001",
                "수락 폐기 경합 팀",
                List.of("OWNER", "초대 대상")
        );
        MemberResult target = memberNamed(fixture.workspace(), "초대 대상");
        UUID inviteeAccountId = resolveAccount("member-invitation-race-invitee");
        IssuedMemberInvitation invitation = issue(
                fixture,
                target,
                "40000000-0000-4000-8000-000000000005"
        );

        List<Object> outcomes = runConcurrently(
                () -> acceptOutcome(invitation.token(), inviteeAccountId),
                () -> revokeOutcome(fixture, invitation.invitationId())
        );

        assertThat(outcomes.stream()
                .filter(outcome -> outcome instanceof AcceptedMemberInvitation
                        || outcome instanceof RevokedMemberInvitation))
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(IdentityOperationException.class::isInstance)
                .map(IdentityOperationException.class::cast)
                .extracting(IdentityOperationException::getCode)
                .allMatch(code -> code.equals("MEMBER_INVITATION_USED")
                        || code.equals("MEMBER_INVITATION_REVOKED"));
        Map<String, Object> terminal = jdbcTemplate.queryForMap(
                "SELECT consumed_at, revoked_at FROM member_invitations "
                        + "WHERE id = UUID_TO_BIN(?)",
                invitation.invitationId().toString()
        );
        assertThat(List.of(
                terminal.get("consumed_at") != null,
                terminal.get("revoked_at") != null
        )).containsExactlyInAnyOrder(true, false);
    }

    private Fixture createOwnedWorkspace(
            String workspaceIdempotencyKey,
            String teamName,
            List<String> memberNames
    ) {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                workspaceIdempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        teamName,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        memberNames
                )
        );
        MemberResult owner = memberNamed(workspace, "OWNER");
        UUID ownerAccountId = resolveAccount(workspaceIdempotencyKey + "-owner");
        var bootstrap = bootstrapInvitationUseCase.issue(
                BOOTSTRAP_KEY,
                UUID.randomUUID().toString(),
                new IssueOwnerBootstrapInvitationCommand(workspace.teamId(), owner.id())
        );
        bootstrapInvitationUseCase.accept(
                bootstrap.token(),
                account(ownerAccountId)
        );
        return new Fixture(workspace, ownerAccountId);
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

    private IssuedMemberInvitation issue(
            Fixture fixture,
            MemberResult target,
            String idempotencyKey
    ) {
        return memberInvitationUseCase.issue(
                account(fixture.ownerAccountId()),
                idempotencyKey,
                new IssueMemberInvitationCommand(fixture.workspace().teamId(), target.id())
        );
    }

    private Object issueOutcome(
            Fixture fixture,
            MemberResult target,
            String idempotencyKey
    ) {
        try {
            return issue(fixture, target, idempotencyKey);
        } catch (IdentityOperationException exception) {
            return exception;
        }
    }

    private Object acceptOutcome(String token, UUID accountId) {
        try {
            return memberInvitationUseCase.accept(token, account(accountId));
        } catch (IdentityOperationException exception) {
            return exception;
        }
    }

    private Object revokeOutcome(Fixture fixture, UUID invitationId) {
        try {
            return memberInvitationUseCase.revoke(
                    fixture.workspace().teamId(),
                    invitationId,
                    account(fixture.ownerAccountId())
            );
        } catch (IdentityOperationException exception) {
            return exception;
        }
    }

    private AuthenticatedAccount account(UUID accountId) {
        return new AuthenticatedAccount(accountId);
    }

    private List<Object> runConcurrently(
            Callable<Object> firstTask,
            Callable<Object> secondTask
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

    private record Fixture(
            CreatedWorkspaceResult workspace,
            UUID ownerAccountId
    ) {
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
