package com.personal.baton.application.calendar;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Result;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BatonApplication.class, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
        "baton.calendar.capture-enabled=true",
        "baton.calendar.season-metadata-enabled=true",
        "baton.calendar.delivery-enabled=false",
        "baton.round-automation.poll-interval=PT24H"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CalendarSeasonMetadataMaintenanceTest {

    private static final UUID TEAM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String ACCESS_KEY = "calendar-maintenance-access-key";

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    ).withDatabaseName("baton_calendar_maintenance").withUsername("baton").withPassword("password");

    @Autowired private MaintainCalendarSeasonMetadataUseCase maintenance;
    @Autowired private CalendarSeasonMetadataMaintenanceWorker worker;
    @Autowired private CalendarOutboxPort outbox;
    @Autowired private WorkspaceRepository repository;
    @Autowired private WorkspaceLifecycleUseCase workspace;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private BriefContinuitySignalRecorder briefRecorder;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM calendar_season_metadata_outbox");
        jdbc.update("UPDATE seasons SET previous_season_id = NULL WHERE previous_season_id IS NOT NULL");
        jdbc.update("DELETE FROM seasons");
        jdbc.update("DELETE FROM teams");
        jdbc.update("INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID.toString(), "이름 보정 팀", DomainSeparatedSha256.hashUtf8Hex(ACCESS_KEY));
    }

    @Test
    @DisplayName("종료된 빈 시즌도 모든 페이지에서 보정하고 같은 이름은 다시 기록하지 않는다")
    void backfillsAllPagesWithoutDuplicateRevisions() {
        for (int index = 1; index <= 101; index++) {
            insertSeason(index, "시즌 " + index);
        }
        jdbc.update("UPDATE seasons SET name = ? WHERE id = UUID_TO_BIN(?)", "허용\u0085문자 😀", seasonId(101).toString());

        assertThat(maintenance.maintain(Mode.OFF)).isEqualTo(new Result(0, 0, 0));
        assertThat(revisions()).isEmpty();
        assertThat(maintenance.maintain(Mode.BACKFILL)).isEqualTo(new Result(101, 101, 0));
        List<Integer> first = revisions();
        assertThat(maintenance.maintain(Mode.BACKFILL)).isEqualTo(new Result(101, 0, 0));
        assertThat(revisions()).isEqualTo(first);

        jdbc.update("UPDATE seasons SET name = ? WHERE id = UUID_TO_BIN(?)", "수정된 이름", seasonId(1).toString());
        assertThat(maintenance.maintain(Mode.BACKFILL)).isEqualTo(new Result(101, 1, 0));
        assertThat(revisions()).hasSize(102).startsWith(first.toArray(Integer[]::new));
    }

    @ParameterizedTest
    @ValueSource(strings = {"분해 e\u0301", "잘못된\r이름"})
    @DisplayName("마지막 페이지의 부적합 이름도 쓰기 전에 찾고 원문은 오류에 노출하지 않는다")
    void preflightsAllPagesBeforeWriting(String invalidName) {
        for (int index = 1; index <= 100; index++) {
            insertSeason(index, "시즌 " + index);
        }
        UUID invalidId = insertSeason(101, invalidName);

        assertThatThrownBy(() -> maintenance.maintain(Mode.BACKFILL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(invalidId.toString()).hasMessageContaining("displayName")
                .hasMessageNotContaining(invalidName);
        assertThat(revisions()).isEmpty();
    }

    @Test
    @DisplayName("일반 시즌 수정도 기간이 같으면 BRIEF 재계산 없이 이름만 기록한다")
    void renamesOpenSeasonWithoutReconcilingBriefSignals() {
        UUID seasonId = insertSeason(1, "기존 시즌");
        jdbc.update("UPDATE seasons SET ended_at = NULL WHERE id = UUID_TO_BIN(?)", seasonId.toString());
        var before = repository.findSeasonById(seasonId).orElseThrow();

        var renamed = workspace.updateSeason(TEAM_ID, seasonId, ACCESS_KEY,
                new WorkspaceLifecycleCommands.UpdateSeasonCommand("변경된 시즌", before.getStartDate(), before.getEndDate()));

        assertThat(renamed.name()).isEqualTo("변경된 시즌");
        assertThat(revisions()).hasSize(1);
        verifyNoInteractions(briefRecorder);
    }

    @Test
    @DisplayName("후속 시즌이 있는 종료 시즌의 이름만 운영자가 정정하면 보정을 다시 실행할 수 있다")
    void correctsEndedSeasonNameWithoutReopeningOrChangingHistory() {
        UUID seasonId = insertSeason(1, "분해 e\u0301");
        UUID successorId = insertSeason(2, "다음 시즌");
        jdbc.update("UPDATE seasons SET previous_season_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)",
                seasonId.toString(), successorId.toString());
        var before = repository.findSeasonById(seasonId).orElseThrow();
        var successorBefore = jdbc.queryForMap("SELECT * FROM seasons WHERE id = UUID_TO_BIN(?)", successorId.toString());
        assertThatThrownBy(() -> maintenance.maintain(Mode.BACKFILL)).isInstanceOf(IllegalStateException.class);

        var corrected = workspace.correctSeasonName(TEAM_ID, seasonId,
                "pilot-recovery-key-0000000000000002", "정정된 시즌");

        assertThat(corrected.name()).isEqualTo("정정된 시즌");
        assertThat(corrected.startDate()).isEqualTo(before.getStartDate());
        assertThat(corrected.endDate()).isEqualTo(before.getEndDate());
        assertThat(corrected.endedAt()).isEqualTo(before.getEndedAt());
        assertThat(corrected.timeZone()).isEqualTo(before.getTimeZone());
        assertThat(corrected.previousSeasonId()).isEqualTo(before.getPreviousSeasonId());
        assertThat(corrected.roundSchedule()).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM seasons WHERE id = UUID_TO_BIN(?)", successorId.toString()))
                .usingRecursiveComparison().isEqualTo(successorBefore);
        assertThat(revisions()).hasSize(1);
        workspace.correctSeasonName(TEAM_ID, seasonId, "pilot-recovery-key-0000000000000002", "정정된 시즌");
        assertThat(revisions()).hasSize(1);
        verifyNoInteractions(briefRecorder);
        assertThat(maintenance.maintain(Mode.BACKFILL)).isEqualTo(new Result(2, 1, 0));
        assertThatThrownBy(() -> workspace.updateSeason(TEAM_ID, seasonId, ACCESS_KEY,
                new WorkspaceLifecycleCommands.UpdateSeasonCommand("일반 수정", before.getStartDate(), before.getEndDate())))
                .isInstanceOf(SeasonEndedException.class);
        assertThatThrownBy(() -> workspace.updateSeasonEnding(TEAM_ID, seasonId, ACCESS_KEY, false))
                .isInstanceOf(SeasonSuccessorExistsException.class);
    }

    @Test
    @DisplayName("이름 정정은 복구 키와 팀 소속을 확인하고 이름 충돌 시 원본과 아웃박스를 보존한다")
    void rejectsUnauthorizedOrConflictingCorrections() {
        UUID seasonId = insertSeason(1, "기존 시즌");
        insertSeason(2, "이미 있는 이름");
        for (String key : new String[] {null, "wrong-key", ACCESS_KEY}) {
            assertThatThrownBy(() -> workspace.correctSeasonName(TEAM_ID, seasonId, key, "정정된 시즌"))
                    .isInstanceOf(WorkspaceRecoveryDeniedException.class);
        }
        assertThatThrownBy(() -> workspace.correctSeasonName(TEAM_ID, seasonId(99),
                "pilot-recovery-key-0000000000000002", "정정된 시즌"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        UUID otherTeamId = UUID.randomUUID();
        jdbc.update("INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                otherTeamId.toString(), "다른 팀", DomainSeparatedSha256.hashUtf8Hex(ACCESS_KEY));
        assertThatThrownBy(() -> workspace.correctSeasonName(otherTeamId, seasonId,
                "pilot-recovery-key-0000000000000002", "정정된 시즌"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> workspace.correctSeasonName(TEAM_ID, seasonId,
                "pilot-recovery-key-0000000000000002", "이미 있는 이름"))
                .isInstanceOf(SeasonNameConflictException.class);
        assertThat(repository.findSeasonById(seasonId).orElseThrow().getName()).isEqualTo("기존 시즌");
        assertThat(revisions()).isEmpty();
    }

    @Test
    @DisplayName("최신 완료 행만 같은 개정 번호로 재전달하고 기록하지 못한 이름 변경은 먼저 보정한다")
    void replaysLatestRevisionAndBackfillsMissedChanges() {
        UUID seasonId = insertSeason(1, "처음 이름");
        maintenance.maintain(Mode.BACKFILL);
        completeNext();
        jdbc.update("UPDATE seasons SET name = ? WHERE id = UUID_TO_BIN(?)", "최신 이름", seasonId.toString());
        assertThat(maintenance.maintain(Mode.REPLAY)).isEqualTo(new Result(1, 1, 1));
        completeNext();
        var before = jdbc.queryForMap("SELECT id, display_name, occurred_at, attempt_count FROM calendar_season_metadata_outbox ORDER BY id DESC LIMIT 1");
        List<Integer> revisions = revisions();

        assertThat(maintenance.maintain(Mode.REPLAY)).isEqualTo(new Result(1, 0, 1));
        assertThat(maintenance.maintain(Mode.REPLAY)).isEqualTo(new Result(1, 0, 1));
        assertThat(revisions()).isEqualTo(revisions);
        assertThat(jdbc.queryForMap("SELECT id, display_name, occurred_at, attempt_count FROM calendar_season_metadata_outbox ORDER BY id DESC LIMIT 1"))
                .isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT delivery_status FROM calendar_season_metadata_outbox ORDER BY id", String.class))
                .containsExactly("DELIVERED", "PENDING");
    }

    @Test
    @DisplayName("재전달 준비가 기존 임대를 무효화해 늦은 완료 응답이 새 전달을 지우지 않는다")
    void fencesOldLeaseWhenReplaying() {
        insertSeason(1, "전달 중인 이름");
        maintenance.maintain(Mode.BACKFILL);
        CalendarDelivery previous = claimNext();

        assertThat(maintenance.maintain(Mode.REPLAY)).isEqualTo(new Result(1, 0, 1));
        CalendarDelivery replay = claimNext();
        assertThat(replay.payload()).isEqualTo(previous.payload());
        assertThat(replay.leaseToken()).isNotEqualTo(previous.leaseToken());
        assertThat(outbox.markDelivered(previous.payload(), previous.leaseToken(), Instant.now(), "STALE")).isFalse();
        assertThat(outbox.markDelivered(replay.payload(), replay.leaseToken(), Instant.now(), "ACCEPTED")).isTrue();
    }

    @Test
    @DisplayName("최신 이름이 계약 오류로 실패했으면 이전 완료 행도 대신 재전달하지 않는다")
    void leavesFailedLatestRevisionForOperator() {
        UUID seasonId = insertSeason(1, "처음 이름");
        maintenance.maintain(Mode.BACKFILL);
        completeNext();
        jdbc.update("UPDATE seasons SET name = ? WHERE id = UUID_TO_BIN(?)", "충돌한 이름", seasonId.toString());
        maintenance.maintain(Mode.BACKFILL);
        var failed = claimNext();
        outbox.markFailed(failed.payload(), failed.leaseToken(), Instant.now(), "SEASON_METADATA_REVISION_CONFLICT");

        assertThat(maintenance.maintain(Mode.REPLAY)).isEqualTo(new Result(1, 0, 0));
        assertThat(jdbc.queryForList("SELECT delivery_status FROM calendar_season_metadata_outbox ORDER BY id", String.class))
                .containsExactly("DELIVERED", "FAILED");
    }

    @Test
    @DisplayName("사전 점검 뒤 이름 수정이 진행되면 시즌 잠금을 기다린 뒤 커밋된 최신 이름을 기록한다")
    void readsCurrentNameAfterAcquiringSourceLock() throws Exception {
        UUID seasonId = insertSeason(1, "사전 점검 이름");
        var candidate = new CalendarSeasonBackfillCandidate(TEAM_ID, seasonId);
        worker.verifyTextCompatibility(candidate);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                var season = repository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, seasonId).orElseThrow();
                season.update("잠금 뒤 최신 이름", season.getStartDate(), season.getEndDate());
                locked.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var backfill = executor.submit(() -> {
                    started.countDown();
                    return worker.maintain(candidate, Mode.BACKFILL);
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> backfill.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                assertThat(backfill.get(5, TimeUnit.SECONDS)).isEqualTo(new Result(1, 1, 0));
            } finally {
                release.countDown();
            }
        }
        assertThat(jdbc.queryForList("SELECT display_name FROM calendar_season_metadata_outbox", String.class))
                .containsExactly("잠금 뒤 최신 이름");
    }

    private UUID insertSeason(int index, String name) {
        UUID id = seasonId(index);
        jdbc.update("""
                INSERT INTO seasons (id, team_id, name, start_date, end_date, ended_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, '2026-08-01', '2026-08-31', UTC_TIMESTAMP(6))
                """, id.toString(), TEAM_ID.toString(), name);
        return id;
    }

    private UUID seasonId(int index) {
        return new UUID(0, index);
    }

    private List<Integer> revisions() {
        return jdbc.queryForList("SELECT id FROM calendar_season_metadata_outbox ORDER BY id", Integer.class);
    }

    private CalendarDelivery claimNext() {
        return outbox.claimPending(1, Instant.now().plusSeconds(1), Duration.ofMinutes(1), true).getFirst();
    }

    private void completeNext() {
        var delivery = claimNext();
        assertThat(outbox.markDelivered(delivery.payload(), delivery.leaseToken(), Instant.now(), "ACCEPTED")).isTrue();
    }
}
