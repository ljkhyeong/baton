package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleHandoffResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.WorkspaceResult;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                RoleHandoffRelayOutboxIntegrationTest.FixedClockConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H",
                "baton.relay.publisher.enabled=false"
        }
)
class RoleHandoffRelayOutboxIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-08T03:04:05.123456789Z");
    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_role_handoff_relay_outbox")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DisplayName("최초 역할 바통 전달은 같은 transaction에 RELAY 사건 한 건을 기록하고 재호출은 중복하지 않는다")
    @Test
    void appendsOnceForCommittedTransferAndSkipsReplay() {
        PreparedHandoff prepared = prepareHandoff("commit");

        RoleHandoffTransitionResult transferred = workspaceUseCase.transferRoleHandoff(
                prepared.workspace().teamId(),
                prepared.workspace().seasonId(),
                prepared.roleId(),
                prepared.handoffId(),
                prepared.workspace().accessKey(),
                new TransferRoleHandoffCommand(prepared.fromMemberId(), true)
        );
        RoleHandoffTransitionResult replayed = workspaceUseCase.transferRoleHandoff(
                prepared.workspace().teamId(),
                prepared.workspace().seasonId(),
                prepared.roleId(),
                prepared.handoffId(),
                prepared.workspace().accessKey(),
                new TransferRoleHandoffCommand(prepared.fromMemberId(), true)
        );

        assertThat(transferred.handoff().status()).isEqualTo(RoleHandoffStatus.TRANSFERRED);
        assertThat(replayed).isEqualTo(transferred);
        Instant expectedTransferredAt = NOW.truncatedTo(ChronoUnit.MICROS);
        assertThat(transferred.handoff().transferredAt()).isEqualTo(expectedTransferredAt);
        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                prepared.workspace().teamId(),
                prepared.workspace().seasonId(),
                prepared.workspace().accessKey()
        );
        assertThat(handoffWithId(reloaded, prepared.handoffId()).transferredAt())
                .isEqualTo(expectedTransferredAt);
        assertThat(outboxCount(prepared.roleId())).isOne();
        StoredEnvelope stored = storedEnvelope(prepared.roleId());
        assertThat(stored.contractVersion()).isEqualTo(1);
        assertThat(stored.eventId()).isNotNull();
        assertThat(stored.eventType()).isEqualTo("ROLE_HANDOFF_TRANSFERRED");
        assertThat(stored.eventVersion()).isEqualTo(1);
        assertThat(stored.subjectReference()).isEqualTo("role:" + prepared.roleId());
        assertThat(stored.occurredAt())
                .isEqualTo(LocalDateTime.ofInstant(expectedTransferredAt, ZoneOffset.UTC));
        assertThat(stored.publicationStatus()).isEqualTo("PENDING");
    }

    @DisplayName("역할 바통 전달 transaction을 rollback하면 상태 전환과 RELAY outbox가 함께 사라진다")
    @Test
    void rollsBackHandoffAndOutboxTogether() {
        PreparedHandoff prepared = prepareHandoff("rollback");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            workspaceUseCase.transferRoleHandoff(
                    prepared.workspace().teamId(),
                    prepared.workspace().seasonId(),
                    prepared.roleId(),
                    prepared.handoffId(),
                    prepared.workspace().accessKey(),
                    new TransferRoleHandoffCommand(prepared.fromMemberId(), true)
            );
            assertThat(outboxCount(prepared.roleId())).isOne();
            status.setRollbackOnly();
        });

        assertThat(outboxCount(prepared.roleId())).isZero();
        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                prepared.workspace().teamId(),
                prepared.workspace().seasonId(),
                prepared.workspace().accessKey()
        );
        RoleHandoffResult handoff = handoffWithId(reloaded, prepared.handoffId());
        assertThat(handoff.status()).isEqualTo(RoleHandoffStatus.PREPARING);
        assertThat(handoff.transferredAt()).isNull();
    }

    private PreparedHandoff prepareHandoff(String suffix) {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                idempotencyKey("workspace-" + suffix),
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "RELAY 바통 " + suffix,
                        "파일럿 시즌",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult current = workspaceUseCase.getWorkspace(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey()
        );
        MemberResult fromMember = memberNamed(current, "박민서");
        MemberResult toMember = memberNamed(current, "김준호");
        RoleResult role = workspaceUseCase.createRole(
                workspace.teamId(),
                workspace.seasonId(),
                idempotencyKey("role-" + suffix),
                workspace.accessKey(),
                new CreateRoleCommand(
                        "진행자 " + suffix,
                        "모임을 진행합니다",
                        fromMember.id(),
                        null,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("안건 확인"),
                        null
                )
        );
        RoleHandoffTransitionResult handoff = workspaceUseCase.prepareRoleHandoff(
                workspace.teamId(),
                workspace.seasonId(),
                role.id(),
                idempotencyKey("handoff-" + suffix),
                workspace.accessKey(),
                new PrepareRoleHandoffCommand(
                        toMember.id(),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)
                )
        );
        return new PreparedHandoff(
                workspace,
                role.id(),
                handoff.handoff().id(),
                fromMember.id()
        );
    }

    private MemberResult memberNamed(WorkspaceResult workspace, String name) {
        return workspace.members().stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private RoleHandoffResult handoffWithId(WorkspaceResult workspace, UUID handoffId) {
        return workspace.roleHandoffs().stream()
                .filter(candidate -> candidate.id().equals(handoffId))
                .findFirst()
                .orElseThrow();
    }

    private long outboxCount(UUID roleId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM relay_event_outbox WHERE subject_reference = ?",
                Long.class,
                "role:" + roleId
        );
    }

    private StoredEnvelope storedEnvelope(UUID roleId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    contract_version,
                    BIN_TO_UUID(event_id) AS event_id,
                    event_type,
                    event_version,
                    subject_reference,
                    occurred_at,
                    publication_status
                FROM relay_event_outbox
                WHERE subject_reference = ?
                """,
                (resultSet, rowNumber) -> new StoredEnvelope(
                        resultSet.getInt("contract_version"),
                        UUID.fromString(resultSet.getString("event_id")),
                        resultSet.getString("event_type"),
                        resultSet.getInt("event_version"),
                        resultSet.getString("subject_reference"),
                        resultSet.getObject("occurred_at", LocalDateTime.class),
                        resultSet.getString("publication_status")
                ),
                "role:" + roleId
        );
    }

    private String idempotencyKey(String suffix) {
        return "relay-role-handoff-" + suffix + "-0000000000000001";
    }

    private record PreparedHandoff(
            CreatedWorkspaceResult workspace,
            UUID roleId,
            UUID handoffId,
            UUID fromMemberId
    ) {
    }

    private record StoredEnvelope(
            int contractVersion,
            UUID eventId,
            String eventType,
            int eventVersion,
            String subjectReference,
            LocalDateTime occurredAt,
            String publicationStatus
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
