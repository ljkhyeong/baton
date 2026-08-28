package com.personal.baton.application.calendar;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.calendar.port.in.BackfillCalendarSnapshotsUseCase;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H"
        }
)
class CalendarOutboxPersistenceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-25T03:00:00Z");
    private static final UUID TEAM_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final UUID ROUND_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final UUID ROLE_ID = UUID.fromString(
            "40000000-0000-0000-0000-000000000001"
    );
    private static final UUID ROUTINE_ID = UUID.fromString(
            "50000000-0000-0000-0000-000000000001"
    );
    private static final UUID EXECUTION_ID = UUID.fromString(
            "60000000-0000-0000-0000-000000000001"
    );
    private static final UUID LEGACY_ROUND_ID = UUID.fromString(
            "70000000-0000-0000-0000-000000000001"
    );

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_calendar_outbox")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private CalendarOutboxPort outboxPort;

    @Autowired
    private BackfillCalendarSnapshotsUseCase backfillCalendarSnapshots;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM calendar_snapshot_outbox");
        jdbcTemplate.update("DELETE FROM routine_executions");
        jdbcTemplate.update("DELETE FROM season_rounds");
        jdbcTemplate.update("DELETE FROM routines");
        jdbcTemplate.update("DELETE FROM roles");
        jdbcTemplate.update("DELETE FROM seasons");
        jdbcTemplate.update("DELETE FROM teams");
    }

    @DisplayName("아웃박스 번호를 개정 번호로 발급하고 시간 형태를 그대로 보존한다")
    @Test
    void appendsImmutableSnapshotsWithGeneratedRevisions() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Integer> revisions = transaction.execute(status -> List.of(
                outboxPort.append(snapshot(
                        new CalendarSnapshot.UtcPoint(Instant.parse("2026-08-26T12:00:00Z"))
                )),
                outboxPort.append(snapshot(
                        new CalendarSnapshot.ZonedLocalPoint(
                                LocalDateTime.of(2026, 8, 27, 20, 0),
                                "Asia/Seoul"
                        )
                )),
                outboxPort.append(snapshot(
                        new CalendarSnapshot.AllDay(
                                LocalDate.of(2026, 8, 28),
                                LocalDate.of(2026, 8, 29)
                        )
                ))
        ));

        assertThat(revisions).isSorted().doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForList(
                "SELECT time_type FROM calendar_snapshot_outbox ORDER BY id",
                String.class
        )).containsExactly("UTC_POINT", "ZONED_LOCAL_POINT", "ALL_DAY");
    }

    @DisplayName("원본 트랜잭션이 롤백되면 CAL 아웃박스도 함께 롤백된다")
    @Test
    void rollsBackWithSourceTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outboxPort.append(snapshot(
                    new CalendarSnapshot.AllDay(
                            LocalDate.of(2026, 8, 28),
                            LocalDate.of(2026, 8, 29)
                    )
            ));
            throw new IllegalStateException("원본 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM calendar_snapshot_outbox",
                Long.class
        )).isZero();
    }

    @DisplayName("같은 원본의 다음 개정 번호는 원본 수정 시각도 1마이크로초 전진시킨다")
    @Test
    void keepsSourceUpdatedAtMonotonic() {
        UUID sourceItemId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            outboxPort.append(snapshot(sourceItemId, new CalendarSnapshot.AllDay(
                    LocalDate.of(2026, 8, 28),
                    LocalDate.of(2026, 8, 29)
            )));
            outboxPort.append(snapshot(sourceItemId, new CalendarSnapshot.AllDay(
                    LocalDate.of(2026, 8, 29),
                    LocalDate.of(2026, 8, 30)
            )));
        });

        assertThat(jdbcTemplate.queryForList(
                "SELECT source_updated_at FROM calendar_snapshot_outbox ORDER BY id",
                LocalDateTime.class
        )).containsExactly(
                LocalDateTime.of(2026, 8, 25, 3, 0),
                LocalDateTime.of(2026, 8, 25, 3, 0, 0, 1_000)
        );
    }

    @DisplayName("CAL 아웃박스 행 수를 전달 상태별 메트릭으로 노출한다")
    @Test
    void exposesRowsByDeliveryStatusAsMetrics() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int revision = transaction.execute(status -> outboxPort.append(snapshot(
                new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 29)
                )
        )));
        CalendarSnapshotDelivery delivery = outboxPort.claimPending(
                1,
                OCCURRED_AT.plusSeconds(1),
                Duration.ofMinutes(1)
        ).getFirst();
        assertThat(outboxPort.markFailed(
                revision,
                delivery.leaseToken(),
                OCCURRED_AT.plusSeconds(2),
                "INVALID_REQUEST"
        )).isTrue();

        assertThat(calendarOutboxEntries("pending")).isZero();
        assertThat(calendarOutboxEntries("processing")).isZero();
        assertThat(calendarOutboxEntries("failed")).isOne();
    }

    @DisplayName("만료된 CAL 임대는 같은 행을 재선점하고 이전 작업자의 완료를 막는다")
    @Test
    void reclaimsExpiredLeaseWithFencing() {
        UUID sourceItemId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Integer> revisions = transaction.execute(status -> List.of(
                outboxPort.append(snapshot(sourceItemId, new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 29)
                ))),
                outboxPort.append(snapshot(sourceItemId, new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 8, 29),
                        LocalDate.of(2026, 8, 30)
                )))
        ));
        Instant firstClaimAt = OCCURRED_AT.plusSeconds(1);

        CalendarSnapshotDelivery first = outboxPort.claimPending(
                10,
                firstClaimAt,
                Duration.ofMinutes(1)
        ).getFirst();
        CalendarSnapshotDelivery reclaimed = outboxPort.claimPending(
                10,
                firstClaimAt.plusSeconds(61),
                Duration.ofMinutes(1)
        ).getFirst();

        assertThat(first.snapshot().revision()).isEqualTo(revisions.getFirst());
        assertThat(reclaimed.snapshot().revision()).isEqualTo(revisions.getFirst());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(outboxPort.markDelivered(
                first.snapshot().revision(),
                first.leaseToken(),
                firstClaimAt.plusSeconds(62),
                "APPLIED"
        )).isFalse();
        assertThat(outboxPort.markDelivered(
                reclaimed.snapshot().revision(),
                reclaimed.leaseToken(),
                firstClaimAt.plusSeconds(62),
                "DUPLICATE"
        )).isTrue();

        CalendarSnapshotDelivery next = outboxPort.claimPending(
                10,
                firstClaimAt.plusSeconds(63),
                Duration.ofMinutes(1)
        ).getFirst();
        assertThat(next.snapshot().revision()).isEqualTo(revisions.get(1));
    }

    @DisplayName("기존 활성 회차는 한 번만 보정하고 보관 뒤 취소 스냅샷을 추가한다")
    @Test
    void backfillsExistingRoundIdempotentlyAndRecordsCancellation() {
        insertRound();

        var first = backfillCalendarSnapshots.backfill();
        var repeated = backfillCalendarSnapshots.backfill();
        jdbcTemplate.update(
                "UPDATE season_rounds SET archived_at = ? WHERE id = UUID_TO_BIN(?)",
                LocalDateTime.of(2026, 8, 25, 12, 0),
                ROUND_ID.toString()
        );
        var archived = backfillCalendarSnapshots.backfill();

        assertThat(first).isEqualTo(new BackfillCalendarSnapshotsUseCase.BackfillResult(1, 2));
        assertThat(repeated).isEqualTo(new BackfillCalendarSnapshotsUseCase.BackfillResult(1, 0));
        assertThat(archived).isEqualTo(new BackfillCalendarSnapshotsUseCase.BackfillResult(1, 2));
        assertThat(jdbcTemplate.queryForList(
                "SELECT calendar_status FROM calendar_snapshot_outbox ORDER BY id",
                String.class
        )).containsExactly("ACTIVE", "ACTIVE", "CANCELLED", "CANCELLED");
    }

    @DisplayName("기존 문자열이 CAL 계약과 맞지 않으면 보정 아웃박스를 추가하지 않는다")
    @ParameterizedTest
    @ValueSource(strings = {"Cafe\u0301", "제어\u000B문자"})
    void rejectsIncompatibleTextBeforeBackfill(String incompatibleText) {
        insertRound();
        jdbcTemplate.update(
                "UPDATE routine_executions SET detail = ? WHERE id = UUID_TO_BIN(?)",
                incompatibleText,
                EXECUTION_ID.toString()
        );

        assertThatThrownBy(backfillCalendarSnapshots::backfill)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXECUTION_ID.toString())
                .hasMessageNotContaining(incompatibleText);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM calendar_snapshot_outbox",
                Long.class
        )).isZero();
    }

    private void insertRound() {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID.toString(),
                "CAL 보정 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID.toString(),
                TEAM_ID.toString(),
                "CAL 보정 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO season_rounds (id, season_id, name, meeting_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROUND_ID.toString(),
                SEASON_ID.toString(),
                "기존 활성 회차",
                LocalDate.of(2026, 8, 25)
        );
        jdbcTemplate.update(
                "INSERT INTO season_rounds (id, season_id, name, meeting_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, NULL)",
                LEGACY_ROUND_ID.toString(),
                SEASON_ID.toString(),
                "회차 도입 이전 기록"
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID.toString(),
                TEAM_ID.toString(),
                SEASON_ID.toString(),
                "CAL 보정 역할",
                "마감 일정을 관리합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routines (id, season_id, title, phase, due_label, "
                        + "deadline_day_offset, deadline_time, owner_role_id, detail) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, "
                        + "UUID_TO_BIN(?), ?)",
                ROUTINE_ID.toString(),
                SEASON_ID.toString(),
                "자료 제출",
                "BEFORE",
                "전날",
                -1,
                "20:00:00",
                ROLE_ID.toString(),
                "자료를 제출합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routine_executions (id, season_round_id, routine_id, title, "
                        + "phase, due_label, deadline_day_offset, deadline_time, deadline_at, "
                        + "owner_role_id, status, detail) VALUES (UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, UUID_TO_BIN(?), ?, ?)",
                EXECUTION_ID.toString(),
                ROUND_ID.toString(),
                ROUTINE_ID.toString(),
                "자료 제출",
                "BEFORE",
                "전날",
                -1,
                "20:00:00",
                LocalDateTime.of(2026, 8, 24, 11, 0),
                ROLE_ID.toString(),
                "WAITING",
                "자료를 제출합니다"
        );
    }

    private CalendarSnapshotDraft snapshot(CalendarSnapshot.Time time) {
        return snapshot(UUID.randomUUID(), time);
    }

    private CalendarSnapshotDraft snapshot(UUID sourceItemId, CalendarSnapshot.Time time) {
        return new CalendarSnapshotDraft(
                UUID.randomUUID(),
                OCCURRED_AT,
                sourceItemId,
                UUID.randomUUID(),
                CalendarSnapshot.Status.ACTIVE,
                "캘린더 일정",
                "일정 설명",
                null,
                time,
                OCCURRED_AT
        );
    }

    private double calendarOutboxEntries(String status) {
        return meterRegistry.get("baton.calendar.outbox.entries")
                .tag("status", status)
                .gauge()
                .value();
    }
}
