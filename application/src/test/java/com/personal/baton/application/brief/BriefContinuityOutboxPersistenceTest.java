package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class BriefContinuityOutboxPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T03:00:00Z");
    private static final UUID FIRST_SIGNAL_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002311"
    );
    private static final UUID SECOND_SIGNAL_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002312"
    );
    private static final UUID WORKSPACE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002313"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002314"
    );

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_brief_delivery_persistence")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private BriefContinuityOutboxPort outboxPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM brief_continuity_outbox");
    }

    @DisplayName("claim은 만료된 lease를 회수하고 같은 신호의 후속 리비전을 앞선 종료 뒤에 연다")
    @Test
    void reclaimsExpiredLeaseAndPreservesPerSignalOrder() {
        insertOutbox(FIRST_SIGNAL_ID, 1, "ROLE_UNASSIGNED");
        insertOutbox(FIRST_SIGNAL_ID, 2, "ROLE_UNASSIGNED");
        insertOutbox(SECOND_SIGNAL_ID, 1, "HANDOFF_INCOMPLETE");

        List<BriefContinuityDelivery> firstClaim = outboxPort.claimPending(
                10,
                NOW,
                Duration.ofSeconds(30)
        );
        assertThat(firstClaim).hasSize(2);
        BriefContinuityDelivery firstSignal = deliveryFor(firstClaim, FIRST_SIGNAL_ID);
        BriefContinuityDelivery secondSignal = deliveryFor(firstClaim, SECOND_SIGNAL_ID);
        assertThat(firstSignal.event().aggregateRevision()).isOne();
        assertThat(firstSignal.attemptCount()).isOne();

        assertThat(outboxPort.markRetry(
                secondSignal.outboxId(),
                secondSignal.leaseToken(),
                NOW.plusSeconds(60),
                "HTTP_503"
        )).isTrue();

        BriefContinuityDelivery reclaimed = outboxPort.claimPending(
                10,
                NOW.plusSeconds(31),
                Duration.ofSeconds(30)
        ).getFirst();
        assertThat(reclaimed.event().sourceReference())
                .isEqualTo(firstSignal.event().sourceReference());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.leaseToken()).isNotEqualTo(firstSignal.leaseToken());
        assertThat(outboxPort.markDelivered(
                firstSignal.outboxId(),
                firstSignal.leaseToken(),
                NOW.plusSeconds(32),
                "HTTP_202"
        )).isFalse();
        assertThat(outboxPort.markDelivered(
                reclaimed.outboxId(),
                reclaimed.leaseToken(),
                NOW.plusSeconds(32),
                "HTTP_202"
        )).isTrue();

        assertThat(outboxPort.claimPending(
                10,
                NOW.plusSeconds(32),
                Duration.ofSeconds(30)
        )).singleElement().satisfies(delivery -> {
            assertThat(delivery.event().sourceReference())
                    .isEqualTo(firstSignal.event().sourceReference());
            assertThat(delivery.event().aggregateRevision()).isEqualTo(2);
        });
    }

    private BriefContinuityDelivery deliveryFor(
            List<BriefContinuityDelivery> deliveries,
            UUID signalId
    ) {
        String sourceReference = "baton-continuity:" + signalId;
        return deliveries.stream()
                .filter(delivery -> delivery.event().sourceReference().equals(sourceReference))
                .findFirst()
                .orElseThrow();
    }

    private void insertOutbox(UUID signalId, long revision, String eventType) {
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id, signal_id, workspace_id, season_id, event_type,
                    event_version, source_severity, source_reference,
                    aggregate_revision, occurred_at, event_state, available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    ?, 2, ?, ?, ?, ?, 'ACTIVE', ?
                )
                """,
                UUID.randomUUID().toString(),
                signalId.toString(),
                WORKSPACE_ID.toString(),
                SEASON_ID.toString(),
                eventType,
                "ROLE_UNASSIGNED".equals(eventType) ? "CRITICAL" : "WARNING",
                "baton-continuity:" + signalId,
                revision,
                utc(NOW),
                utc(NOW)
        );
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
