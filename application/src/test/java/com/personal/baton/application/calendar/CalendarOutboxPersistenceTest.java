package com.personal.baton.application.calendar;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
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

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_calendar_outbox")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private CalendarOutboxPort outboxPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM calendar_snapshot_outbox");
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
}
