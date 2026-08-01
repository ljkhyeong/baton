package com.personal.baton.application.round;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.MemberResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
                RoundGrantResourceRepositoryIntegrationTest.FixedClockConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key",
                "baton.workspace.recovery-key=pilot-recovery-key"
        }
)
class RoundGrantResourceRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String ROOM_URL =
            "https://round.example/room/abcd-efgh-jkmn";
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-7777-4777-8777-111111111111");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_round_grant")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private MemberIdentityUseCase memberIdentityUseCase;

    @Autowired
    private RoundGrantResourceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("실제 MySQL에서 활성 계정 결속의 exact room 자료만 찾고 여러 후보를 모두 반환한다")
    @Test
    void findsOnlyActiveAuthorizedResourcesAndPreservesAmbiguity() {
        insertAccount();

        WorkspaceFixture first = createFixture(
                "round-grant-team-first-000000000001",
                "ROUND fallback 첫 팀",
                true,
                false
        );
        createFixture(
                "round-grant-team-unbound-0000000002",
                "ROUND fallback 미결속 팀",
                false,
                false
        );
        createFixture(
                "round-grant-team-inactive-000000003",
                "ROUND fallback 비활성 팀",
                true,
                true
        );

        assertThat(repository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        ))
                .extracting(RoundGrantResourceRepository.AuthorizedRoundResource::resourceId)
                .containsExactly(first.resourceId());

        WorkspaceFixture second = createFixture(
                "round-grant-team-second-00000000002",
                "ROUND fallback 둘째 팀",
                true,
                false
        );

        assertThat(repository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        ))
                .extracting(RoundGrantResourceRepository.AuthorizedRoundResource::resourceId)
                .containsExactlyInAnyOrder(first.resourceId(), second.resourceId());
    }

    private WorkspaceFixture createFixture(
            String idempotencyKey,
            String teamName,
            boolean bindAccount,
            boolean deactivateMember
    ) {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        teamName,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("참여 구성원")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                        workspace.teamId(),
                        workspace.seasonId(),
                        workspace.accessKey()
                )
                .members()
                .getFirst();
        if (bindAccount) {
            memberIdentityUseCase.bindMember(
                    workspace.teamId(),
                    member.id(),
                    new AuthenticatedAccount(ACCOUNT_ID)
            );
        }
        var role = workspaceUseCase.createRole(
                workspace.teamId(),
                workspace.seasonId(),
                UUID.randomUUID().toString(),
                workspace.accessKey(),
                new CreateRoleCommand(
                        "ROUND 참여 역할",
                        "복사한 room 참여권 조회를 검증합니다",
                        member.id(),
                        null,
                        null,
                        null,
                        List.of(),
                        null
                )
        );
        var resource = workspaceUseCase.createRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                UUID.randomUUID().toString(),
                workspace.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "ROUND 회의실",
                        ROOM_URL,
                        null
                )
        );
        if (deactivateMember) {
            workspaceUseCase.updateMemberDeactivation(
                    workspace.teamId(),
                    workspace.seasonId(),
                    member.id(),
                    workspace.accessKey(),
                    true
            );
        }
        return new WorkspaceFixture(
                workspace.teamId(),
                workspace.seasonId(),
                resource.id()
        );
    }

    private void insertAccount() {
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES (UUID_TO_BIN(?), ?)",
                ACCOUNT_ID.toString(),
                NOW.minusSeconds(60)
        );
    }

    private record WorkspaceFixture(
            UUID teamId,
            UUID seasonId,
            UUID resourceId
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
