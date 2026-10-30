package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import com.personal.baton.application.delivery.DeliveryResult;
import com.personal.baton.application.delivery.DispatchResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
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

    @DisplayName("처리를 시작할 때 만료된 임대를 회수하고, 같은 신호의 다음 리비전은 앞선 처리가 끝난 뒤 연다")
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
        assertThat(attemptCount(firstSignal)).isOne();

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
        assertThat(attemptCount(reclaimed)).isEqualTo(2);
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

    @DisplayName("재시도를 기다리는 신호는 다른 신호의 전달을 막지 않고 같은 신호의 다음 리비전만 붙잡는다")
    @Test
    void keepsDeliveringOtherSignalsWhileOneSignalWaitsForRetry() {
        insertOutbox(FIRST_SIGNAL_ID, 1, "ROLE_UNASSIGNED");
        insertOutbox(FIRST_SIGNAL_ID, 2, "ROLE_UNASSIGNED");
        insertOutbox(SECOND_SIGNAL_ID, 1, "HANDOFF_INCOMPLETE");
        List<BriefContinuityDelivery> attempts = new ArrayList<>();
        BriefContinuityClient failingFirstSignal = delivery -> {
            attempts.add(delivery);
            return delivery.event().sourceReference().endsWith(FIRST_SIGNAL_ID.toString())
                    ? DeliveryResult.retryable("HTTP_503")
                    : DeliveryResult.delivered("HTTP_202");
        };

        assertThat(dispatchAt(NOW, failingFirstSignal).failedCount()).isOne();
        assertThat(dispatchAt(NOW.plusSeconds(1), failingFirstSignal).deliveredCount()).isOne();
        assertThat(dispatchAt(NOW.plusSeconds(2), failingFirstSignal).claimedCount()).isZero();
        assertThat(dispatchAt(NOW.plusSeconds(10), failingFirstSignal).failedCount()).isOne();

        assertThat(attempts).extracting(
                delivery -> delivery.event().sourceReference(),
                delivery -> delivery.event().aggregateRevision(),
                BriefContinuityDelivery::attemptCount
        ).containsExactly(
                tuple("baton-continuity:" + FIRST_SIGNAL_ID, 1L, 1),
                tuple("baton-continuity:" + SECOND_SIGNAL_ID, 1L, 1),
                tuple("baton-continuity:" + FIRST_SIGNAL_ID, 1L, 2)
        );
    }

    private DispatchResult dispatchAt(Instant now, BriefContinuityClient client) {
        return new BriefContinuityOutboxDispatchService(
                outboxPort,
                client,
                Clock.fixed(now, ZoneOffset.UTC)
        ).dispatchPending();
    }

    private int attemptCount(BriefContinuityDelivery delivery) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM brief_continuity_outbox WHERE id = ?",
                Integer.class,
                delivery.outboxId()
        );
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
