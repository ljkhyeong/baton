package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
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

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                MemberIdentityUseCaseIntegrationTest.FixedClockConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key",
                "baton.workspace.recovery-key=pilot-recovery-key"
        }
)
class MemberIdentityUseCaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final UUID FIRST_ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_identity")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MemberIdentityUseCase memberIdentityUseCase;

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("실제 MySQL에서 사용자 계정과 활동 중인 구성원을 연결하고 활동 종료 뒤 권한 조회에서 제외한다")
    @Test
    void persistsBindingAndExcludesDeactivatedMemberFromAuthorizationLookup() {
        CreatedWorkspaceResult workspace = createWorkspace(
                "identity-integration-primary-0001",
                "신원 결속 통합 팀",
                List.of("박민서")
        );
        MemberResult member = memberNamed(workspace, "박민서");
        insertAccount(FIRST_ACCOUNT_ID);

        MemberIdentityResult bound = memberIdentityUseCase.bindMember(
                workspace.teamId(),
                member.id(),
                new AuthenticatedAccount(FIRST_ACCOUNT_ID)
        );

        assertThat(bound.boundAt()).isEqualTo(NOW);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_identity_bindings "
                        + "WHERE member_id = UUID_TO_BIN(?) AND user_account_id = UUID_TO_BIN(?)",
                Integer.class,
                member.id().toString(),
                FIRST_ACCOUNT_ID.toString()
        )).isEqualTo(1);
        assertThat(memberIdentityUseCase.findActiveMember(
                workspace.teamId(),
                new AuthenticatedAccount(FIRST_ACCOUNT_ID)
        )).contains(bound);

        workspaceUseCase.updateMemberDeactivation(
                workspace.teamId(),
                workspace.seasonId(),
                member.id(),
                workspace.accessKey(),
                true
        );

        assertThat(memberIdentityUseCase.findActiveMember(
                workspace.teamId(),
                new AuthenticatedAccount(FIRST_ACCOUNT_ID)
        )).isEmpty();
        assertThat(memberIdentityUseCase.bindMember(
                workspace.teamId(),
                member.id(),
                new AuthenticatedAccount(FIRST_ACCOUNT_ID)
        )).isEqualTo(bound);
    }

    @DisplayName("같은 팀 계정이 두 구성원을 동시에 결속하면 MySQL 잠금 뒤 한 요청만 성공한다")
    @Test
    void serializesConcurrentClaimsForSameTeamAccount() throws Exception {
        CreatedWorkspaceResult workspace = createWorkspace(
                "identity-integration-concurrent-001",
                "신원 결속 경합 팀",
                List.of("박민서", "김준호")
        );
        MemberResult firstMember = memberNamed(workspace, "박민서");
        MemberResult secondMember = memberNamed(workspace, "김준호");
        insertAccount(SECOND_ACCOUNT_ID);

        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> bindAfterBarrier(
                    start,
                    workspace.teamId(),
                    firstMember.id(),
                    SECOND_ACCOUNT_ID
            ));
            Future<Object> second = executor.submit(() -> bindAfterBarrier(
                    start,
                    workspace.teamId(),
                    secondMember.id(),
                    SECOND_ACCOUNT_ID
            ));
            start.await(10, TimeUnit.SECONDS);

            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertThat(outcomes)
                    .filteredOn(MemberIdentityResult.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(MemberIdentityConflictException.class::isInstance)
                    .hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM member_identity_bindings "
                            + "WHERE team_id = UUID_TO_BIN(?) "
                            + "AND user_account_id = UUID_TO_BIN(?)",
                    Integer.class,
                    workspace.teamId().toString(),
                    SECOND_ACCOUNT_ID.toString()
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object bindAfterBarrier(
            CyclicBarrier start,
            UUID teamId,
            UUID memberId,
            UUID accountId
    ) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            return memberIdentityUseCase.bindMember(
                    teamId,
                    memberId,
                    new AuthenticatedAccount(accountId)
            );
        } catch (MemberIdentityConflictException exception) {
            return exception;
        }
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

    private void insertAccount(UUID accountId) {
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES (UUID_TO_BIN(?), ?)",
                accountId.toString(),
                NOW.minusSeconds(60)
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
