package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {BatonApplication.class, BriefContinuitySignalPersistenceTest.ClockConfig.class},
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BriefContinuitySignalPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:04:05Z");
    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_brief_continuity")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private ReconcileBriefContinuitySignalsUseCase reconciliationUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DisplayName("BRIEF 원본 변경과 시간 재조정은 연속 리비전과 원자성을 보존한다")
    @Test
    void preservesSignalRevisionsAndSourceTransactionAtomicity() {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                "workspace-brief-signal-create-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "BRIEF 신호 스터디",
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 9, 30),
                        List.of("김준호")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                        workspace.teamId(),
                        workspace.seasonId(),
                        workspace.accessKey()
                ).members().getFirst();
        RoleResult role = workspaceUseCase.createRole(
                workspace.teamId(),
                workspace.seasonId(),
                "workspace-brief-signal-role-000001",
                workspace.accessKey(),
                createRoleCommand()
        );

        workspaceUseCase.updateRole(
                workspace.teamId(),
                workspace.seasonId(),
                role.id(),
                workspace.accessKey(),
                updateRoleCommand(member.id())
        );
        workspaceUseCase.updateRole(
                workspace.teamId(),
                workspace.seasonId(),
                role.id(),
                workspace.accessKey(),
                updateRoleCommand(null)
        );
        workspaceUseCase.updateSeason(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey(),
                new UpdateSeasonCommand(
                        "2026 여름 시즌",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30)
                )
        );
        assertThat(reconciliationUseCase.reconcileAll().appendedCount()).isZero();

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                """
                SELECT
                    BIN_TO_UUID(signal_id) AS signal_id,
                    source_reference,
                    aggregate_revision,
                    source_severity,
                    event_state
                FROM brief_continuity_outbox
                WHERE workspace_id = UUID_TO_BIN(?)
                AND season_id = UUID_TO_BIN(?)
                ORDER BY aggregate_revision
                """,
                workspace.teamId().toString(),
                workspace.seasonId().toString()
        );
        assertThat(events)
                .extracting(
                        event -> event.get("AGGREGATE_REVISION"),
                        event -> event.get("SOURCE_SEVERITY"),
                        event -> event.get("EVENT_STATE")
                )
                .containsExactly(
                        tuple(1L, "CRITICAL", "ACTIVE"),
                        tuple(2L, "CRITICAL", "RESOLVED"),
                        tuple(3L, "CRITICAL", "ACTIVE"),
                        tuple(4L, "WARNING", "ACTIVE")
                );
        assertThat(events)
                .extracting(event -> event.get("SIGNAL_ID"))
                .containsOnly(events.getFirst().get("SIGNAL_ID"));
        assertThat(events)
                .extracting(event -> event.get("SOURCE_REFERENCE"))
                .containsOnly("baton-continuity:" + events.getFirst().get("SIGNAL_ID"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            workspaceUseCase.updateRole(
                    workspace.teamId(),
                    workspace.seasonId(),
                    role.id(),
                    workspace.accessKey(),
                    updateRoleCommand(member.id())
            );
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brief_continuity_outbox WHERE signal_id = UUID_TO_BIN(?)",
                Long.class,
                events.getFirst().get("SIGNAL_ID")
        )).isEqualTo(4);
        assertThat(workspaceUseCase.getWorkspace(
                        workspace.teamId(),
                        workspace.seasonId(),
                        workspace.accessKey()
                ).roles().getFirst().currentMemberId())
                .isNull();

        workspaceUseCase.updateSeasonEnding(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey(),
                true
        );
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT aggregate_revision, event_state
                FROM brief_continuity_outbox
                WHERE signal_id = UUID_TO_BIN(?)
                ORDER BY aggregate_revision DESC
                LIMIT 1
                """,
                events.getFirst().get("SIGNAL_ID")
        )).containsEntry("AGGREGATE_REVISION", 5L)
                .containsEntry("EVENT_STATE", "RESOLVED");
        assertThat(reconciliationUseCase.reconcileAll().candidateCount()).isZero();
    }

    private UpdateRoleCommand updateRoleCommand(java.util.UUID currentMemberId) {
        return new UpdateRoleCommand(
                "진행자",
                "매주 스터디 진행을 맡습니다",
                currentMemberId,
                null,
                null,
                null,
                List.of("진행 순서 확인"),
                null
        );
    }

    private CreateRoleCommand createRoleCommand() {
        return new CreateRoleCommand(
                "진행자",
                "매주 스터디 진행을 맡습니다",
                null,
                null,
                null,
                null,
                List.of("진행 순서 확인"),
                null
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
