package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.ClaimResult;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.GenerationTarget;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BriefEditionGenerationExecutionPersistenceTest {

    private static final UUID TEAM_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002621"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002622"
    );
    private static final UUID EDITION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002623"
    );
    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_brief_generation_execution")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private BriefEditionGenerationExecutionPort executionPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM brief_edition_generation_execution");
        jdbcTemplate.update("DELETE FROM brief_continuity_outbox");
        jdbcTemplate.update("DELETE FROM seasons");
        jdbcTemplate.update("DELETE FROM teams");
        jdbcTemplate.update(
                """
                INSERT INTO teams (id, name, access_key_hash)
                VALUES (UUID_TO_BIN(?), 'BRIEF 실행 팀', REPEAT('a', 64))
                """,
                TEAM_ID.toString()
        );
        jdbcTemplate.update(
                """
                INSERT INTO seasons (id, team_id, name, start_date, end_date)
                VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'BRIEF 실행 시즌',
                    '2026-08-01', '2026-09-30'
                )
                """,
                SEASON_ID.toString(),
                TEAM_ID.toString()
        );
    }

    @DisplayName("같은 전달 경계 실행을 재사용하고 만료 lease만 회수한다")
    @Test
    void reusesExecutionAndReclaimsOnlyExpiredLease() {
        var boundary = executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID);
        assertThat(boundary.watermark()).isZero();
        assertThat(boundary.complete()).isTrue();

        GenerationTarget target = new GenerationTarget(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul"),
                boundary.watermark()
        );
        ClaimResult.Claimed first = (ClaimResult.Claimed) executionPort.claim(
                target,
                true,
                NOW,
                Duration.ofMinutes(1)
        );
        assertThat(executionPort.findExecutionState(target)).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo("PROCESSING");
            assertThat(state.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        });
        assertThat(executionPort.claim(
                target,
                true,
                NOW.plusSeconds(30),
                Duration.ofMinutes(1)
        )).isInstanceOf(ClaimResult.InProgress.class);

        ClaimResult.Claimed reclaimed = (ClaimResult.Claimed) executionPort.claim(
                target,
                true,
                NOW.plusSeconds(61),
                Duration.ofMinutes(1)
        );
        assertThat(reclaimed.executionId()).isEqualTo(first.executionId());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(executionPort.markSucceeded(
                first.executionId(),
                first.leaseToken(),
                NOW.plusSeconds(62),
                EDITION_ID,
                3,
                17,
                "\"brief-edition-v1-test\"",
                true
        )).isFalse();
        assertThat(executionPort.markSucceeded(
                reclaimed.executionId(),
                reclaimed.leaseToken(),
                NOW.plusSeconds(62),
                EDITION_ID,
                3,
                17,
                "\"brief-edition-v1-test\"",
                true
        )).isTrue();

        ClaimResult.Completed completed = (ClaimResult.Completed) executionPort.claim(
                target,
                true,
                NOW.plusSeconds(63),
                Duration.ofMinutes(1)
        );
        assertThat(completed.editionId()).isEqualTo(EDITION_ID);
        assertThat(completed.generation()).isEqualTo(3);
        assertThat(completed.sourceCursor()).isEqualTo(17);
    }

    @DisplayName("전달 대기 outbox가 있으면 watermark 실행을 PENDING으로 기록한다")
    @Test
    void recordsPendingExecutionForIncompleteDelivery() {
        insertPendingOutbox();

        var boundary = executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID);
        assertThat(boundary.watermark()).isPositive();
        assertThat(boundary.complete()).isFalse();
        var result = executionPort.claim(
                new GenerationTarget(
                        TEAM_ID,
                        SEASON_ID,
                        LocalDate.parse("2026-08-24"),
                        ZoneId.of("Asia/Seoul"),
                        boundary.watermark()
                ),
                false,
                NOW,
                Duration.ofMinutes(1)
        );

        assertThat(result).isInstanceOf(ClaimResult.DeliveryIncomplete.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM brief_edition_generation_execution",
                String.class
        )).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("전달 집계는 같은 팀·시즌의 대기와 실패를 구분하고 성공 시각만 반환한다")
    void readsDeliveryCountsAndSuccessTime() {
        insertPendingOutbox();
        var pending = executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID);
        assertThat(pending.pendingCount()).isEqualTo(1); assertThat(pending.failedCount()).isZero();
        assertThat(pending.lastDeliveredAt()).isNull();
        jdbcTemplate.update("UPDATE brief_continuity_outbox SET delivery_status = 'FAILED', completed_at = ?", utc(NOW));
        var failed = executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID);
        assertThat(failed.pendingCount()).isZero(); assertThat(failed.failedCount()).isEqualTo(1);
        assertThat(failed.complete()).isFalse(); assertThat(failed.lastDeliveredAt()).isNull();
        jdbcTemplate.update("UPDATE brief_continuity_outbox SET delivery_status = 'DELIVERED', completed_at = ?", utc(NOW.plusSeconds(2)));
        assertThat(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID).lastDeliveredAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(executionPort.findDeliveryBoundary(TEAM_ID, UUID.randomUUID()).watermark()).isZero();
        assertThat(executionPort.findDeliveryBoundary(UUID.randomUUID(), SEASON_ID).lastDeliveredAt()).isNull();
    }

    private void insertPendingOutbox() {
        UUID signalId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id, signal_id, workspace_id, season_id, event_type,
                    event_version, source_severity, source_reference,
                    aggregate_revision, occurred_at, event_state, available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    'ROLE_UNASSIGNED', 2, 'WARNING', ?, 1, ?, 'ACTIVE', ?
                )
                """,
                UUID.randomUUID().toString(),
                signalId.toString(),
                TEAM_ID.toString(),
                SEASON_ID.toString(),
                "baton-continuity:" + signalId,
                utc(NOW),
                utc(NOW)
        );
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
