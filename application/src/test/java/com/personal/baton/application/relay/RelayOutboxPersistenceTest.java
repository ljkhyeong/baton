package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                RelayOutboxPersistenceTest.TransactionProbeConfiguration.class
        },
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H",
                "baton.relay.publisher.enabled=false"
        }
)
class RelayOutboxPersistenceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T03:04:05Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_relay_outbox_persistence")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private RelayOutboxPort outboxPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DispatchRelayOutboxUseCase dispatchRelayOutbox;

    @Autowired
    private TransactionProbePublisher transactionProbePublisher;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM relay_event_outbox");
        transactionProbePublisher.reset();
    }

    @DisplayName("상위 transaction에서 호출해도 broker I/O는 중단된 transaction 밖에서 수행하고 완료를 독립 commit한다")
    @Test
    void suspendsAmbientTransactionAroundBrokerIo() {
        RelayOutboxEvent event = event(
                UUID.fromString("00000000-0000-0000-0000-000000001700"),
                "ROLE.HANDOFF.TRANSFERRED",
                "role-handoff:01J4H8Z7A1",
                OCCURRED_AT
        );
        append(event);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            DispatchRelayOutboxUseCase.DispatchResult result =
                    dispatchRelayOutbox.dispatchPending();

            assertThat(result).isEqualTo(
                    new DispatchRelayOutboxUseCase.DispatchResult(1, 1, 0)
            );
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();
        });

        assertThat(transactionProbePublisher.transactionWasActive()).isFalse();
        assertThat(storedStatus(rowId(event.eventId()))).isEqualTo("PUBLISHED");
    }

    @DisplayName("RELAY outbox는 여섯 계약 필드를 불변 payload로 저장하고 발생 시각부터 발행 가능하게 한다")
    @Test
    void appendsTypedImmutablePayloadDueAtOccurredTime() {
        Instant preciseOccurredAt = Instant.parse("2026-08-08T03:04:05.123456789Z");
        RelayOutboxEvent event = event(
                UUID.fromString("00000000-0000-0000-0000-000000001701"),
                "ORDER.PAID",
                "order:01J4H8Z7A2",
                preciseOccurredAt
        );

        append(event);

        StoredEvent stored = jdbcTemplate.queryForObject(
                """
                SELECT
                    contract_version,
                    BIN_TO_UUID(event_id) AS event_id,
                    event_type,
                    event_version,
                    subject_reference,
                    occurred_at,
                    publication_status,
                    attempt_count,
                    available_at
                FROM relay_event_outbox
                """,
                (resultSet, rowNumber) -> new StoredEvent(
                        resultSet.getInt("contract_version"),
                        UUID.fromString(resultSet.getString("event_id")),
                        resultSet.getString("event_type"),
                        resultSet.getInt("event_version"),
                        resultSet.getString("subject_reference"),
                        resultSet.getObject("occurred_at", LocalDateTime.class),
                        resultSet.getString("publication_status"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getObject("available_at", LocalDateTime.class)
                )
        );
        LocalDateTime expectedTimestamp = LocalDateTime.of(
                2026,
                8,
                8,
                3,
                4,
                5,
                123_456_000
        );
        assertThat(stored).isEqualTo(new StoredEvent(
                1,
                event.eventId(),
                "ORDER.PAID",
                1,
                "order:01J4H8Z7A2",
                expectedTimestamp,
                "PENDING",
                0,
                expectedTimestamp
        ));
    }

    @DisplayName("현재 lease token은 lease가 만료됐어도 다른 claim이 회수하기 전에는 발행 완료를 확정한다")
    @Test
    void finalizesCurrentTokenAfterExpiryUntilReclaimed() {
        RelayOutboxEvent event = event(
                UUID.fromString("00000000-0000-0000-0000-000000001702"),
                "ROLE.HANDOFF.TRANSFERRED",
                "role-handoff:01J4H8Z7A3",
                OCCURRED_AT
        );
        append(event);

        RelayOutboxPublication publication = outboxPort.claim(
                OCCURRED_AT,
                Duration.ofSeconds(30)
        ).orElseThrow();

        assertThat(publication.event()).isEqualTo(event);
        assertThat(publication.attemptCount()).isOne();
        assertThat(publication.leaseToken()).isNotNull();
        assertThat(storedStatus(publication.rowId())).isEqualTo("PUBLISHING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lease_expires_at FROM relay_event_outbox WHERE id = ?",
                LocalDateTime.class,
                publication.rowId()
        )).isEqualTo(LocalDateTime.of(2026, 8, 8, 3, 4, 35));

        assertThat(outboxPort.markPublished(
                publication,
                OCCURRED_AT.plusSeconds(31)
        )).isTrue();
        assertThat(storedStatus(publication.rowId())).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at FROM relay_event_outbox WHERE id = ?",
                LocalDateTime.class,
                publication.rowId()
        )).isEqualTo(LocalDateTime.of(2026, 8, 8, 3, 4, 36));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM relay_event_outbox "
                        + "WHERE id = ? AND lease_token IS NULL AND lease_expires_at IS NULL",
                Long.class,
                publication.rowId()
        )).isOne();
    }

    @DisplayName("만료된 발행을 회수하면 attempt와 token을 교체하고 이전 token의 완료와 재예약을 차단한다")
    @Test
    void reclaimsExpiredPublicationAndFencesStaleToken() {
        RelayOutboxEvent event = event(
                UUID.fromString("00000000-0000-0000-0000-000000001703"),
                "DECISION.RECORDED",
                "decision:01J4H8Z7A4",
                OCCURRED_AT
        );
        append(event);
        RelayOutboxPublication first = outboxPort.claim(
                OCCURRED_AT,
                Duration.ofSeconds(30)
        ).orElseThrow();

        RelayOutboxPublication reclaimed = outboxPort.claim(
                OCCURRED_AT.plusSeconds(30),
                Duration.ofSeconds(30)
        ).orElseThrow();

        assertThat(reclaimed.rowId()).isEqualTo(first.rowId());
        assertThat(reclaimed.event()).isEqualTo(first.event());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(outboxPort.markPublished(first, OCCURRED_AT.plusSeconds(31))).isFalse();
        RelayOutboxPublication wrongRow = new RelayOutboxPublication(
                reclaimed.rowId() + 10_000,
                reclaimed.event(),
                reclaimed.attemptCount(),
                reclaimed.leaseToken()
        );
        assertThat(outboxPort.markPublished(
                wrongRow,
                OCCURRED_AT.plusSeconds(31)
        )).isFalse();
        assertThat(outboxPort.reschedule(
                first,
                OCCURRED_AT.plusSeconds(60),
                "BROKER_UNAVAILABLE"
        )).isFalse();

        assertThat(outboxPort.reschedule(
                reclaimed,
                OCCURRED_AT.plusSeconds(60),
                "BROKER_UNAVAILABLE"
        )).isTrue();
        assertThat(storedStatus(reclaimed.rowId())).isEqualTo("PENDING");
        assertThat(outboxPort.claim(
                OCCURRED_AT.plusSeconds(59),
                Duration.ofSeconds(30)
        )).isEmpty();

        RelayOutboxPublication third = outboxPort.claim(
                OCCURRED_AT.plusSeconds(60),
                Duration.ofSeconds(30)
        ).orElseThrow();
        assertThat(third.attemptCount()).isEqualTo(3);
        assertThat(outboxPort.markPublished(third, OCCURRED_AT.plusSeconds(61))).isTrue();
    }

    @DisplayName("재예약 오류 코드는 64자로 제한하고 허용된 코드는 다음 claim까지 보존한다")
    @Test
    void boundsRetryErrorCode() {
        append(event(
                UUID.fromString("00000000-0000-0000-0000-000000001704"),
                "ROUTINE.OVERDUE",
                "routine-round:01J4H8Z7A5",
                OCCURRED_AT
        ));
        RelayOutboxPublication publication = outboxPort.claim(
                OCCURRED_AT,
                Duration.ofSeconds(30)
        ).orElseThrow();

        assertThatThrownBy(() -> outboxPort.reschedule(
                publication,
                OCCURRED_AT.plusSeconds(60),
                "E".repeat(65)
        )).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(storedStatus(publication.rowId())).isEqualTo("PUBLISHING");

        String boundedCode = "E".repeat(64);
        assertThat(outboxPort.reschedule(
                publication,
                OCCURRED_AT.plusSeconds(60),
                boundedCode
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM relay_event_outbox WHERE id = ?",
                String.class,
                publication.rowId()
        )).isEqualTo(boundedCode);
    }

    @DisplayName("claim은 잠긴 선두 행을 기다리지 않고 건너뛰어 다음 발행 가능 행을 점유한다")
    @Test
    void skipsLockedHeadRowWhenClaimingConcurrently() throws Exception {
        RelayOutboxEvent firstEvent = event(
                UUID.fromString("00000000-0000-0000-0000-000000001705"),
                "ORDER.PAID",
                "order:01J4H8Z7A6",
                OCCURRED_AT
        );
        RelayOutboxEvent secondEvent = event(
                UUID.fromString("00000000-0000-0000-0000-000000001706"),
                "ORDER.PAID",
                "order:01J4H8Z7A7",
                OCCURRED_AT
        );
        append(firstEvent);
        append(secondEvent);
        long firstRowId = rowId(firstEvent.eventId());
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch allowUnlock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lock = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM relay_event_outbox WHERE id = ? FOR UPDATE",
                            Long.class,
                            firstRowId
                    );
                    rowLocked.countDown();
                    await(allowUnlock);
                }));

        try {
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Optional<RelayOutboxPublication> whileFirstLocked = outboxPort.claim(
                    OCCURRED_AT,
                    Duration.ofSeconds(30)
            );

            assertThat(whileFirstLocked).isPresent();
            assertThat(whileFirstLocked.orElseThrow().event().eventId())
                    .isEqualTo(secondEvent.eventId());
        } finally {
            allowUnlock.countDown();
            lock.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        assertThat(outboxPort.claim(OCCURRED_AT, Duration.ofSeconds(30)))
                .get()
                .extracting(publication -> publication.event().eventId())
                .isEqualTo(firstEvent.eventId());
    }

    private void append(RelayOutboxEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outboxPort.append(event));
    }

    private RelayOutboxEvent event(
            UUID eventId,
            String eventType,
            String subjectReference,
            Instant occurredAt
    ) {
        return new RelayOutboxEvent(
                1,
                eventId,
                eventType,
                1,
                subjectReference,
                occurredAt.truncatedTo(ChronoUnit.MICROS)
        );
    }

    private long rowId(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM relay_event_outbox WHERE event_id = UUID_TO_BIN(?)",
                Long.class,
                eventId.toString()
        );
    }

    private String storedStatus(long rowId) {
        return jdbcTemplate.queryForObject(
                "SELECT publication_status FROM relay_event_outbox WHERE id = ?",
                String.class,
                rowId
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 검증 latch 대기 시간이 초과됐습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 검증 latch 대기가 중단됐습니다", exception);
        }
    }

    private record StoredEvent(
            int contractVersion,
            UUID eventId,
            String eventType,
            int eventVersion,
            String subjectReference,
            LocalDateTime occurredAt,
            String publicationStatus,
            int attemptCount,
            LocalDateTime availableAt
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionProbeConfiguration {

        @Bean
        @Primary
        TransactionProbePublisher transactionProbePublisher() {
            return new TransactionProbePublisher();
        }
    }

    static final class TransactionProbePublisher implements RelayEventPublisher {

        private final AtomicBoolean transactionWasActive = new AtomicBoolean();

        @Override
        public RelayPublishResult publish(RelayOutboxPublication publication) {
            transactionWasActive.set(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            return RelayPublishResult.confirmed();
        }

        boolean transactionWasActive() {
            return transactionWasActive.get();
        }

        void reset() {
            transactionWasActive.set(false);
        }
    }
}
