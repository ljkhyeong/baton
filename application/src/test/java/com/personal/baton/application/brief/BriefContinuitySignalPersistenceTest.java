package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.NextSeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonRoundCommand;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @DisplayName("상태를 변경할 때만 BRIEF를 재조정하고 같은 상태의 반복 요청은 생략한다")
    @ParameterizedTest
    @EnumSource(StateChange.class)
    void reconcilesOnlyWhenStateChanges(StateChange change) {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        Consumer<Boolean> update = switch (change) {
            case SEASON_ENDING -> value -> workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, value);
            case MEMBER_DEACTIVATION -> value -> workspaceUseCase.updateMemberDeactivation(
                    teamId, seasonId, sources.memberId(), key, value);
            case ROUTINE_ARCHIVE -> value -> workspaceUseCase.updateRoutineArchive(
                    teamId, seasonId, sources.routineId(), key, value);
            case ROUND_ARCHIVE -> value -> workspaceUseCase.updateSeasonRoundArchive(
                    teamId, seasonId, sources.roundId(), key, value);
            case EXECUTION_COMPLETION -> value -> workspaceUseCase.updateRoutineExecutionCompletion(
                    teamId, seasonId, sources.roundId(), sources.executionId(), key, value);
            case HANDOFF_COMPLETION -> value -> workspaceUseCase.updateHandoffItemCompletion(
                    teamId, seasonId, sources.handoffItemId(), key, value);
            case HANDOFF_ARCHIVE -> value -> workspaceUseCase.updateHandoffItemArchive(
                    teamId, seasonId, sources.handoffItemId(), key, value);
            case RESOURCE_ARCHIVE -> value -> workspaceUseCase.updateRoleResourceArchive(
                    teamId, seasonId, sources.resourceId(), key, value);
        };
        try {
            for (boolean value : List.of(true, false)) {
                clearInvocations(recorder);
                update.accept(value);
                verify(recorder).reconcileSeason(teamId, seasonId);

                clearInvocations(recorder);
                update.accept(value);
                verifyNoInteractions(recorder);
            }
        } finally {
            workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, true);
        }
    }

    @DisplayName("역할 자료 내용 수정은 BRIEF를 재조정하지 않고 다른 역할로 이동할 때만 재조정한다")
    @Test
    void reconcilesResourceRoleMoveButNotContentEdit() {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        try {
            RoleResult targetRole = workspaceUseCase.createRole(
                    teamId, seasonId, UUID.randomUUID().toString(), key, createRoleCommand());
            clearInvocations(recorder);

            var edited = workspaceUseCase.updateRoleResource(
                    teamId, seasonId, sources.resourceId(), key,
                    new UpdateRoleResourceCommand(
                            sources.roleId(), "수정한 자료", "https://example.com/updated", "수정한 설명")
            );
            assertThat(edited.title()).isEqualTo("수정한 자료");
            assertThat(edited.url()).isEqualTo("https://example.com/updated");
            verifyNoInteractions(recorder);

            var moved = workspaceUseCase.updateRoleResource(
                    teamId, seasonId, sources.resourceId(), key,
                    new UpdateRoleResourceCommand(targetRole.id(), edited.title(), edited.url(), edited.description())
            );
            assertThat(moved.roleId()).isEqualTo(targetRole.id());
            verify(recorder).reconcileSeason(teamId, seasonId);
        } finally {
            workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, true);
        }
    }

    @DisplayName("바통 항목 문구와 분류 수정은 BRIEF를 재조정하지 않고 다른 역할로 이동할 때만 재조정한다")
    @Test
    void reconcilesHandoffRoleMoveButNotContentEdit() {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        try {
            RoleResult targetRole = workspaceUseCase.createRole(
                    teamId, seasonId, UUID.randomUUID().toString(), key, createRoleCommand());
            clearInvocations(recorder);

            var edited = workspaceUseCase.updateHandoffItem(
                    teamId, seasonId, sources.handoffItemId(), key,
                    new UpdateHandoffItemCommand(sources.roleId(), "수정한 안내", HandoffCategory.ADVICE)
            );
            assertThat(edited.label()).isEqualTo("수정한 안내");
            assertThat(edited.category()).isEqualTo(HandoffCategory.ADVICE);
            verifyNoInteractions(recorder);

            var moved = workspaceUseCase.updateHandoffItem(
                    teamId, seasonId, sources.handoffItemId(), key,
                    new UpdateHandoffItemCommand(targetRole.id(), edited.label(), edited.category())
            );
            assertThat(moved.roleId()).isEqualTo(targetRole.id());
            verify(recorder).reconcileSeason(teamId, seasonId);
        } finally {
            workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, true);
        }
    }

    @DisplayName("수동 회차는 날짜가 바뀌거나 이관 기록에 처음 지정될 때만 BRIEF를 재조정한다")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcilesRoundDateChangeButNotNameEdit(boolean legacyRound) {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        try {
            if (legacyRound) {
                jdbcTemplate.update("UPDATE season_rounds SET meeting_date = NULL WHERE id = UUID_TO_BIN(?)",
                        sources.roundId().toString());
            }
            clearInvocations(recorder);
            var renamed = workspaceUseCase.updateSeasonRound(
                    teamId, seasonId, sources.roundId(), key,
                    new UpdateSeasonRoundCommand("수정한 모임 이름", LocalDate.of(2026, 7, 20)));
            assertThat(renamed.name()).isEqualTo("수정한 모임 이름");
            if (legacyRound) {
                verify(recorder).reconcileSeason(teamId, seasonId);
            } else {
                verifyNoInteractions(recorder);
            }

            clearInvocations(recorder);
            var rescheduled = workspaceUseCase.updateSeasonRound(
                    teamId, seasonId, sources.roundId(), key,
                    new UpdateSeasonRoundCommand(renamed.name(), LocalDate.of(2026, 7, 21)));
            assertThat(rescheduled.meetingDate()).isEqualTo(LocalDate.of(2026, 7, 21));
            verify(recorder).reconcileSeason(teamId, seasonId);
        } finally {
            workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, true);
        }
    }

    @DisplayName("콘텐츠 최초 생성은 BRIEF를 재조정하고 같은 키의 재전송은 기존 결과만 반환한다")
    @ParameterizedTest
    @EnumSource(SignalCreation.class)
    void reconcilesCreationButNotReplay(SignalCreation creation) {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        String idempotencyKey = UUID.randomUUID().toString();
        Supplier<?> create = switch (creation) {
            case ROLE -> () -> workspaceUseCase.createRole(
                    teamId, seasonId, idempotencyKey, key, createRoleCommand());
            case ROUND -> () -> workspaceUseCase.createSeasonRound(
                    teamId, seasonId, idempotencyKey, key,
                    new CreateSeasonRoundCommand("두 번째 모임", LocalDate.of(2026, 7, 21)));
            case HANDOFF_ITEM -> () -> workspaceUseCase.createHandoffItem(
                    teamId, seasonId, idempotencyKey, key,
                    new CreateHandoffItemCommand(sources.roleId(), "새 안내", HandoffCategory.ADVICE));
            case RESOURCE -> () -> workspaceUseCase.createRoleResource(
                    teamId, seasonId, idempotencyKey, key,
                    new CreateRoleResourceCommand(sources.roleId(), "새 자료", "https://example.com/new", null));
            case ROLE_HANDOFF -> {
                MemberResult nextMember = workspaceUseCase.createMember(
                        teamId, seasonId, UUID.randomUUID().toString(), key, new CreateMemberCommand("박민서"));
                workspaceUseCase.updateRole(
                        teamId, seasonId, sources.roleId(), key,
                        new UpdateRoleCommand("기록자", "기록 담당", sources.memberId(), null,
                                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), List.of("회의 기록"), null));
                yield () -> workspaceUseCase.prepareRoleHandoff(
                        teamId, seasonId, sources.roleId(), idempotencyKey, key,
                        new PrepareRoleHandoffCommand(
                                nextMember.id(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30)));
            }
        };
        Object created = null;
        try {
            clearInvocations(recorder);
            created = create.get();
            verify(recorder).reconcileSeason(teamId, seasonId);

            clearInvocations(recorder);
            assertThat(create.get()).isEqualTo(created);
            verifyNoInteractions(recorder);
        } finally {
            if (created instanceof RoleHandoffTransitionResult prepared) {
                workspaceUseCase.cancelRoleHandoff(
                        teamId, seasonId, sources.roleId(), prepared.handoff().id(), key,
                        new ConfirmRoleHandoffCommand(sources.memberId()));
            }
            workspaceUseCase.updateSeasonEnding(teamId, seasonId, key, true);
        }
    }

    @DisplayName("다음 시즌 최초 생성은 이전·새 시즌 신호를 갱신하고 재전송은 모두 생략한다")
    @Test
    void reconcilesNextSeasonCreationButNotReplay() {
        SignalSources sources = signalSources();
        CreatedWorkspaceResult workspace = sources.workspace();
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        String idempotencyKey = UUID.randomUUID().toString();
        workspaceUseCase.updateRole(teamId, seasonId, sources.roleId(), key, updateRoleCommand(null));
        CreateNextSeasonCommand command = new CreateNextSeasonCommand(
                "가을 시즌", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31),
                List.of(sources.roleId()), List.of(sources.routineId()));
        NextSeasonResult created = null;
        try {
            clearInvocations(recorder);
            created = workspaceUseCase.createNextSeason(teamId, seasonId, idempotencyKey, key, command);
            verify(recorder).reconcileSeason(teamId, seasonId);
            verify(recorder).reconcileSeason(teamId, created.season().id());
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT signal_state, latest_revision FROM brief_continuity_signal WHERE season_id = UUID_TO_BIN(?)",
                    seasonId.toString()))
                    .containsEntry("SIGNAL_STATE", "RESOLVED").containsEntry("LATEST_REVISION", 2L);
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT signal_state, latest_revision FROM brief_continuity_signal WHERE season_id = UUID_TO_BIN(?)",
                    created.season().id().toString()))
                    .containsEntry("SIGNAL_STATE", "ACTIVE").containsEntry("LATEST_REVISION", 1L);

            clearInvocations(recorder);
            assertThat(workspaceUseCase.createNextSeason(teamId, seasonId, idempotencyKey, key, command))
                    .isEqualTo(created);
            verifyNoInteractions(recorder);
        } finally {
            workspaceUseCase.updateSeasonEnding(
                    teamId, created == null ? seasonId : created.season().id(), key, true);
        }
    }

    private SignalSources signalSources() {
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                UUID.randomUUID().toString(), CREATION_KEY,
                new CreateWorkspaceCommand("신호 변경 확인", "여름 시즌",
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), List.of("김준호"))
        );
        UUID teamId = workspace.teamId();
        UUID seasonId = workspace.seasonId();
        String key = workspace.accessKey();
        MemberResult member = workspaceUseCase.getWorkspace(teamId, seasonId, key).members().getFirst();
        RoleResult role = assignedRole(workspace, member, "기록자");
        var routine = workspaceUseCase.createRoutine(
                teamId, seasonId, UUID.randomUUID().toString(), key,
                new CreateRoutineCommand("모임 준비", RoutinePhase.BEFORE, "모임 전", role.id(), "자료 준비", null, null)
        );
        var round = workspaceUseCase.createSeasonRound(
                teamId, seasonId, UUID.randomUUID().toString(), key,
                new CreateSeasonRoundCommand("첫 모임", LocalDate.of(2026, 7, 20))
        );
        var item = workspaceUseCase.createHandoffItem(
                teamId, seasonId, UUID.randomUUID().toString(), key,
                new CreateHandoffItemCommand(role.id(), "모임 준비 안내", HandoffCategory.ROUTINE)
        );
        var resource = workspaceUseCase.createRoleResource(
                teamId, seasonId, UUID.randomUUID().toString(), key,
                new CreateRoleResourceCommand(role.id(), "기존 자료", "https://example.com/original", "기존 설명")
        );
        return new SignalSources(workspace, member.id(), role.id(), routine.id(), round.id(),
                round.routineExecutions().getFirst().id(), item.id(), resource.id());
    }

    private enum SignalCreation {
        ROLE,
        ROUND,
        HANDOFF_ITEM,
        RESOURCE,
        ROLE_HANDOFF
    }

    private enum StateChange {
        SEASON_ENDING,
        MEMBER_DEACTIVATION,
        ROUTINE_ARCHIVE,
        ROUND_ARCHIVE,
        EXECUTION_COMPLETION,
        HANDOFF_COMPLETION,
        HANDOFF_ARCHIVE,
        RESOURCE_ARCHIVE
    }

    private record SignalSources(
            CreatedWorkspaceResult workspace,
            UUID memberId,
            UUID roleId,
            UUID routineId,
            UUID roundId,
            UUID executionId,
            UUID handoffItemId,
            UUID resourceId
    ) {
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
