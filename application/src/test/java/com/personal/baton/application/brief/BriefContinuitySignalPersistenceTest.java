package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.doAnswer;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    @Autowired
    private BriefContinuitySignalReconciliationWorker reconciliationWorker;

    @MockitoSpyBean
    private BriefContinuitySignalRecorder recorder;

    @DisplayName("동시 원본 변경과 시간 재조정은 먼저 시작한 변경을 기다리고 활성 신호를 보존한다")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesActiveSignalsDuringConcurrentChanges(boolean backgroundReconciliation) throws Exception {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                UUID.randomUUID().toString(),
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "BRIEF 동시 수정 스터디", "파일럿 시즌",
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                        List.of("김준호")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                workspace.teamId(), workspace.seasonId(), workspace.accessKey()
        ).members().getFirst();
        RoleResult firstRole = assignedRole(workspace, member, "진행자");
        RoleResult secondRole = assignedRole(workspace, member, "기록자");
        CountDownLatch sourceSaved = new CountDownLatch(1);
        CountDownLatch allowSourceCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean pauseFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (pauseFirst.compareAndSet(true, false)) {
                sourceSaved.countDown();
                if (!allowSourceCommit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("원본 변경 커밋 대기 시간이 초과됐습니다");
                }
            }
            return invocation.callRealMethod();
        }).when(recorder).reconcileSeason(workspace.teamId(), workspace.seasonId());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> unassign(workspace, firstRole));
            assertThat(sourceSaved.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return backgroundReconciliation
                        ? reconciliationWorker.reconcile(workspace.teamId(), workspace.seasonId())
                        : unassign(workspace, secondRole);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean waitedForSourceCommit = false;
            try {
                second.get(300, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                waitedForSourceCommit = true;
            } finally {
                allowSourceCommit.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            int expectedSignals = backgroundReconciliation ? 1 : 2;
            assertThat(jdbcTemplate.queryForList(
                    """
                    SELECT signal_state, latest_revision
                    FROM brief_continuity_signal
                    WHERE season_id = UUID_TO_BIN(?)
                    """,
                    workspace.seasonId().toString()
            )).hasSize(expectedSignals).allSatisfy(signal -> assertThat(signal)
                    .containsEntry("SIGNAL_STATE", "ACTIVE")
                    .containsEntry("LATEST_REVISION", 1L));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM brief_continuity_outbox WHERE season_id = UUID_TO_BIN(?)",
                    Long.class, workspace.seasonId().toString()
            )).isEqualTo(expectedSignals);
            assertThat(workspaceUseCase.getWorkspace(
                    workspace.teamId(), workspace.seasonId(), workspace.accessKey()
            ).continuitySignals()).hasSize(expectedSignals);
            assertThat(waitedForSourceCommit).isTrue();
        } finally {
            allowSourceCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            workspaceUseCase.updateSeasonEnding(
                    workspace.teamId(), workspace.seasonId(), workspace.accessKey(), true
            );
        }
    }

    private RoleResult assignedRole(
            CreatedWorkspaceResult workspace,
            MemberResult member,
            String name
    ) {
        return workspaceUseCase.createRole(
                workspace.teamId(), workspace.seasonId(), UUID.randomUUID().toString(),
                workspace.accessKey(),
                new CreateRoleCommand(
                        name, "모임을 운영합니다", member.id(), null, null, null,
                        List.of("진행 순서 확인"), null
                )
        );
    }

    private RoleResult unassign(CreatedWorkspaceResult workspace, RoleResult role) {
        return workspaceUseCase.updateRole(
                workspace.teamId(), workspace.seasonId(), role.id(), workspace.accessKey(),
                new UpdateRoleCommand(
                        role.name(), "모임을 운영합니다", null, null, null, null,
                        List.of("진행 순서 확인"), null
                )
        );
    }

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
