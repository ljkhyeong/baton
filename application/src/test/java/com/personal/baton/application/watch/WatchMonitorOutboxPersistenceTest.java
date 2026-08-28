package com.personal.baton.application.watch;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
class WatchMonitorOutboxPersistenceTest {

    private static final UUID ACTIVE_TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000001611");
    private static final UUID ACTIVE_SEASON_ID = UUID.fromString("00000000-0000-0000-0000-000000001612");
    private static final UUID ACTIVE_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000001613");
    private static final UUID FIRST_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000001614");
    private static final UUID SECOND_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000001615");
    private static final UUID EARLIER_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000001610");
    private static final UUID ENDED_TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000001621");
    private static final UUID ENDED_SEASON_ID = UUID.fromString("00000000-0000-0000-0000-000000001622");
    private static final UUID ENDED_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000001623");
    private static final UUID ENDED_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000001624");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T03:00:00Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_watch_outbox_persistence")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WatchMonitorOutboxPort outboxPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM watch_monitor_outbox WHERE compensation_for_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM watch_monitor_outbox");
        jdbcTemplate.update("DELETE FROM role_resources");
        jdbcTemplate.update("DELETE FROM roles");
        jdbcTemplate.update("DELETE FROM seasons");
        jdbcTemplate.update("DELETE FROM teams");
        seedActiveWorkspace();
    }

    @DisplayName("같은 WATCH snapshot은 다시 쌓지 않고 변경된 payload만 불변 행으로 추가한다")
    @Test
    void appendsOnlyChangedImmutableSnapshots() {
        WatchMonitorChange initial = activeChange(
                UUID.fromString("00000000-0000-0000-0000-000000001631"),
                FIRST_RESOURCE_ID,
                "https://example.com/first"
        );
        WatchMonitorChange duplicate = activeChange(
                UUID.fromString("00000000-0000-0000-0000-000000001632"),
                FIRST_RESOURCE_ID,
                "https://example.com/first"
        );
        WatchMonitorChange changed = activeChange(
                UUID.fromString("00000000-0000-0000-0000-000000001633"),
                FIRST_RESOURCE_ID,
                "https://example.com/changed"
        );

        assertThat(outboxPort.appendIfChanged(initial)).isTrue();
        assertThat(outboxPort.appendIfChanged(duplicate)).isFalse();
        assertThat(outboxPort.appendIfChanged(changed)).isTrue();

        List<StoredPayload> payloads = jdbcTemplate.query(
                """
                SELECT
                    id,
                    BIN_TO_UUID(event_id) AS event_id,
                    monitoring_state,
                    target_url,
                    occurred_at
                FROM watch_monitor_outbox
                WHERE resource_id = UUID_TO_BIN(?)
                ORDER BY id
                """,
                (resultSet, rowNumber) -> new StoredPayload(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("event_id")),
                        resultSet.getString("monitoring_state"),
                        resultSet.getString("target_url"),
                        resultSet.getObject("occurred_at", LocalDateTime.class)
                ),
                FIRST_RESOURCE_ID.toString()
        );
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).eventId()).isEqualTo(initial.eventId());
        assertThat(payloads.get(0).monitoringState()).isEqualTo("ACTIVE");
        assertThat(payloads.get(0).targetUrl()).isEqualTo(initial.targetUrl());
        assertThat(payloads.get(0).occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 3, 0));
        assertThat(payloads.get(1).eventId()).isEqualTo(changed.eventId());
        assertThat(payloads.get(1).sourceRevision()).isGreaterThan(payloads.get(0).sourceRevision());
        assertThat(outboxPort.hasMismatchedResourceReferencePrefix(
                "baton-manager:pilot:"
        )).isFalse();
        assertThat(outboxPort.hasMismatchedResourceReferencePrefix(
                "baton-manager:other-environment:"
        )).isTrue();
    }

    @DisplayName("source namespace 검사는 대소문자만 달라도 서로 다른 환경으로 판정한다")
    @Test
    void comparesSourceNamespaceCaseSensitively() {
        outboxPort.appendIfChanged(activeChange(
                UUID.randomUUID(),
                FIRST_RESOURCE_ID,
                "https://example.com/first"
        ));

        assertThat(outboxPort.hasMismatchedResourceReferencePrefix(
                "baton-manager:pilot:"
        )).isFalse();
        assertThat(outboxPort.hasMismatchedResourceReferencePrefix(
                "baton-manager:PILOT:"
        )).isTrue();
    }

    @DisplayName("reconciliation은 후보 조회 뒤 바뀐 자료를 오래된 snapshot으로 되돌리지 않는다")
    @Test
    void rejectsStaleReconciliationCandidate() {
        String initialUrl = "https://example.com/first";
        String changedUrl = "https://example.com/changed";
        WatchMonitorCandidate staleCandidate = new WatchMonitorCandidate(
                FIRST_RESOURCE_ID,
                initialUrl,
                false
        );
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, initialUrl));
        jdbcTemplate.update(
                "UPDATE role_resources SET url = ? WHERE id = UUID_TO_BIN(?)",
                changedUrl,
                FIRST_RESOURCE_ID.toString()
        );
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, changedUrl));

        boolean appended = outboxPort.appendReconciledIfCurrent(
                staleCandidate,
                activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, initialUrl)
        );

        assertThat(appended).isFalse();
        assertThat(jdbcTemplate.queryForList(
                "SELECT target_url FROM watch_monitor_outbox "
                        + "WHERE resource_id = UUID_TO_BIN(?) ORDER BY id",
                String.class,
                FIRST_RESOURCE_ID.toString()
        )).containsExactly(initialUrl, changedUrl);
    }

    @DisplayName("reconciliation은 겹친 자료 수정 commit을 기다린 뒤 오래된 후보를 폐기한다")
    @Test
    void waitsForConcurrentResourceChangeBeforeRejectingStaleCandidate() throws Exception {
        String initialUrl = "https://example.com/first";
        String changedUrl = "https://example.com/concurrent-change";
        WatchMonitorCandidate staleCandidate = new WatchMonitorCandidate(
                FIRST_RESOURCE_ID,
                initialUrl,
                false
        );
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, initialUrl));
        CountDownLatch mutationReady = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> mutation = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.update(
                                "UPDATE role_resources SET url = ? WHERE id = UUID_TO_BIN(?)",
                                changedUrl,
                                FIRST_RESOURCE_ID.toString()
                        );
                        outboxPort.appendIfChanged(activeChange(
                                UUID.randomUUID(),
                                FIRST_RESOURCE_ID,
                                changedUrl
                        ));
                        mutationReady.countDown();
                        await(allowCommit);
                    }));
            assertThat(mutationReady.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> reconciliation = executor.submit(() ->
                    outboxPort.appendReconciledIfCurrent(
                            staleCandidate,
                            activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, initialUrl)
                    ));
            assertThatThrownBy(() -> reconciliation.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowCommit.countDown();
            mutation.get(5, TimeUnit.SECONDS);
            assertThat(reconciliation.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT target_url FROM watch_monitor_outbox "
                            + "WHERE resource_id = UUID_TO_BIN(?) ORDER BY id",
                    String.class,
                    FIRST_RESOURCE_ID.toString()
            )).containsExactly(initialUrl, changedUrl);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @DisplayName("claim은 만료된 lease를 회수하고 같은 자료의 후속 snapshot을 앞선 종료 뒤에만 연다")
    @Test
    void reclaimsExpiredLeaseAndPreservesPerResourceOrder() {
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, "https://example.com/v1"));
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, "https://example.com/v2"));
        outboxPort.appendIfChanged(activeChange(UUID.randomUUID(), SECOND_RESOURCE_ID, "https://example.org/v1"));

        List<WatchMonitorDelivery> firstClaim = outboxPort.claimPending(
                10,
                OCCURRED_AT,
                Duration.ofSeconds(30)
        );
        assertThat(firstClaim).hasSize(2);
        WatchMonitorDelivery firstResourceDelivery = deliveryFor(firstClaim, FIRST_RESOURCE_ID);
        WatchMonitorDelivery secondResourceDelivery = deliveryFor(firstClaim, SECOND_RESOURCE_ID);
        assertThat(firstResourceDelivery.targetUrl()).isEqualTo("https://example.com/v1");
        assertThat(firstResourceDelivery.attemptCount()).isOne();

        assertThat(outboxPort.markRetry(
                secondResourceDelivery.sourceRevision(),
                secondResourceDelivery.leaseToken(),
                OCCURRED_AT.plusSeconds(60),
                "HTTP_503"
        )).isTrue();

        List<WatchMonitorDelivery> reclaimed = outboxPort.claimPending(
                10,
                OCCURRED_AT.plusSeconds(31),
                Duration.ofSeconds(30)
        );
        assertThat(reclaimed).singleElement().satisfies(delivery -> {
            assertThat(delivery.sourceRevision()).isEqualTo(firstResourceDelivery.sourceRevision());
            assertThat(delivery.attemptCount()).isEqualTo(2);
            assertThat(delivery.leaseToken()).isNotEqualTo(firstResourceDelivery.leaseToken());
        });

        WatchMonitorDelivery reclaimedFirst = reclaimed.getFirst();
        assertThat(outboxPort.markDelivered(
                reclaimedFirst.sourceRevision(),
                reclaimedFirst.leaseToken(),
                OCCURRED_AT.plusSeconds(32),
                "DELIVERED"
        )).isTrue();

        List<WatchMonitorDelivery> nextSnapshot = outboxPort.claimPending(
                10,
                OCCURRED_AT.plusSeconds(32),
                Duration.ofSeconds(30)
        );
        assertThat(nextSnapshot).singleElement().satisfies(delivery -> {
            assertThat(delivery.resourceId()).isEqualTo(FIRST_RESOURCE_ID);
            assertThat(delivery.targetUrl()).isEqualTo("https://example.com/v2");
            assertThat(delivery.attemptCount()).isOne();
        });

        assertThat(outboxPort.markDelivered(
                nextSnapshot.getFirst().sourceRevision(),
                nextSnapshot.getFirst().leaseToken(),
                OCCURRED_AT.plusSeconds(33),
                "DELIVERED"
        )).isTrue();
        assertThat(outboxPort.claimPending(
                10,
                OCCURRED_AT.plusSeconds(59),
                Duration.ofSeconds(30)
        )).isEmpty();
        assertThat(outboxPort.claimPending(
                10,
                OCCURRED_AT.plusSeconds(60),
                Duration.ofSeconds(30)
        )).singleElement().satisfies(delivery -> {
            assertThat(delivery.resourceId()).isEqualTo(SECOND_RESOURCE_ID);
            assertThat(delivery.attemptCount()).isEqualTo(2);
        });
    }

    @DisplayName("유효하지 않은 대상 처리는 실패 표시와 INACTIVE 보상 snapshot을 한 트랜잭션으로 묶는다")
    @Test
    void atomicallyMarksInvalidTargetAndAppendsInactiveCompensation() {
        UUID sourceEventId = UUID.fromString("00000000-0000-0000-0000-000000001641");
        outboxPort.appendIfChanged(activeChange(
                sourceEventId,
                FIRST_RESOURCE_ID,
                "https://example.com/invalid"
        ));
        WatchMonitorDelivery delivery = outboxPort.claimPending(
                1,
                OCCURRED_AT,
                Duration.ofSeconds(30)
        ).getFirst();

        assertThatThrownBy(() -> outboxPort.markInvalidTargetAndAppendInactive(
                delivery.sourceRevision(),
                delivery.leaseToken(),
                sourceEventId,
                OCCURRED_AT.plusSeconds(1)
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedStatus(delivery.sourceRevision())).isEqualTo("PROCESSING");
        assertThat(outboxCount()).isOne();

        assertThat(outboxPort.markInvalidTargetAndAppendInactive(
                delivery.sourceRevision(),
                delivery.leaseToken(),
                UUID.fromString("00000000-0000-0000-0000-000000001642"),
                OCCURRED_AT.plusSeconds(2)
        )).isTrue();

        assertThat(storedStatus(delivery.sourceRevision())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM watch_monitor_outbox WHERE id = ?",
                String.class,
                delivery.sourceRevision()
        )).isEqualTo("INVALID_TARGET_URL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT target_url FROM watch_monitor_outbox WHERE id = ?",
                String.class,
                delivery.sourceRevision()
        )).isEqualTo("https://example.com/invalid");
        assertThat(outboxCount()).isEqualTo(2L);

        assertThat(outboxPort.claimPending(
                1,
                OCCURRED_AT.plusSeconds(2),
                Duration.ofSeconds(30)
        )).singleElement().satisfies(compensation -> {
            assertThat(compensation.resourceId()).isEqualTo(FIRST_RESOURCE_ID);
            assertThat(compensation.monitoringState()).isEqualTo(WatchMonitoringState.INACTIVE);
            assertThat(compensation.targetUrl()).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT compensation_for_id FROM watch_monitor_outbox WHERE id = ?",
                    Long.class,
                    compensation.sourceRevision()
            )).isEqualTo(delivery.sourceRevision());
        });
    }

    @DisplayName("유효하지 않은 대상의 보상은 같은 URL 재활성화를 막고 URL 변경은 새 ACTIVE를 허용한다")
    @Test
    void suppressesRejectedTargetUntilResourceUrlChanges() {
        String rejectedUrl = "https://example.com/first";
        WatchMonitorChange rejectedChange = activeChange(
                UUID.randomUUID(),
                FIRST_RESOURCE_ID,
                rejectedUrl
        );
        outboxPort.appendIfChanged(rejectedChange);
        WatchMonitorDelivery rejectedDelivery = outboxPort.claimPending(
                1,
                OCCURRED_AT,
                Duration.ofSeconds(30)
        ).getFirst();
        assertThat(outboxPort.markInvalidTargetAndAppendInactive(
                rejectedDelivery.sourceRevision(),
                rejectedDelivery.leaseToken(),
                UUID.randomUUID(),
                OCCURRED_AT.plusSeconds(1)
        )).isTrue();

        assertThat(outboxPort.appendIfChanged(activeChange(
                UUID.randomUUID(),
                FIRST_RESOURCE_ID,
                rejectedUrl
        ))).isFalse();
        assertThat(outboxPort.appendReconciledIfCurrent(
                new WatchMonitorCandidate(FIRST_RESOURCE_ID, rejectedUrl, false),
                activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, rejectedUrl)
        )).isFalse();
        assertThat(outboxCount()).isEqualTo(2L);

        String changedUrl = "https://example.com/accepted-candidate";
        jdbcTemplate.update(
                "UPDATE role_resources SET url = ? WHERE id = UUID_TO_BIN(?)",
                changedUrl,
                FIRST_RESOURCE_ID.toString()
        );

        assertThat(outboxPort.appendReconciledIfCurrent(
                new WatchMonitorCandidate(FIRST_RESOURCE_ID, changedUrl, false),
                activeChange(UUID.randomUUID(), FIRST_RESOURCE_ID, changedUrl)
        )).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT target_url FROM watch_monitor_outbox "
                        + "WHERE resource_id = UUID_TO_BIN(?) ORDER BY id",
                String.class,
                FIRST_RESOURCE_ID.toString()
        )).containsExactly(rejectedUrl, null, changedUrl);
    }

    @DisplayName("시작 복구는 설정성 HTTP 실패만 재처리하고 revision 충돌은 영구 실패로 남긴다")
    @Test
    void requeuesOnlyOperationalHttpFailuresOnStartup() {
        outboxPort.appendIfChanged(activeChange(
                UUID.randomUUID(),
                FIRST_RESOURCE_ID,
                "https://example.com/first"
        ));
        outboxPort.appendIfChanged(activeChange(
                UUID.randomUUID(),
                SECOND_RESOURCE_ID,
                "https://example.org/second"
        ));
        List<WatchMonitorDelivery> deliveries = outboxPort.claimPending(
                2,
                OCCURRED_AT,
                Duration.ofSeconds(30)
        );
        WatchMonitorDelivery credentialFailure = deliveryFor(deliveries, FIRST_RESOURCE_ID);
        WatchMonitorDelivery revisionConflict = deliveryFor(deliveries, SECOND_RESOURCE_ID);
        assertThat(outboxPort.markFailed(
                credentialFailure.sourceRevision(),
                credentialFailure.leaseToken(),
                OCCURRED_AT.plusSeconds(1),
                "HTTP_401"
        )).isTrue();
        assertThat(outboxPort.markFailed(
                revisionConflict.sourceRevision(),
                revisionConflict.leaseToken(),
                OCCURRED_AT.plusSeconds(1),
                "SOURCE_REVISION_CONFLICT"
        )).isTrue();

        assertThat(outboxPort.requeueOperationalFailures(OCCURRED_AT.plusSeconds(2))).isOne();

        assertThat(outboxPort.claimPending(
                2,
                OCCURRED_AT.plusSeconds(2),
                Duration.ofSeconds(30)
        )).singleElement().satisfies(delivery ->
                assertThat(delivery.resourceId()).isEqualTo(FIRST_RESOURCE_ID));
        assertThat(storedStatus(revisionConflict.sourceRevision())).isEqualTo("FAILED");
    }

    @DisplayName("reconciliation 후보는 UUID keyset page와 시즌 종료 상태를 함께 반환한다")
    @Test
    void findsReconciliationCandidatesThroughRoleAndSeason() {
        seedEndedWorkspace();

        assertThat(outboxPort.findReconciliationCandidates(null, 2))
                .containsExactly(
                        new WatchMonitorCandidate(
                                FIRST_RESOURCE_ID,
                                "https://example.com/first",
                                false
                        ),
                        new WatchMonitorCandidate(
                                SECOND_RESOURCE_ID,
                                "https://example.org/second",
                                false
                        )
                );
        assertThat(outboxPort.findReconciliationCandidates(SECOND_RESOURCE_ID, 2))
                .containsExactly(
                        new WatchMonitorCandidate(
                                ENDED_RESOURCE_ID,
                                "https://example.net/ended",
                                true
                        )
                );
    }

    @DisplayName("page cursor보다 앞에 추가된 자료는 다음 reconciliation에서 처음부터 다시 찾는다")
    @Test
    void findsEarlierResourceOnNextReconciliationRun() {
        assertThat(outboxPort.findReconciliationCandidates(null, 1))
                .extracting(WatchMonitorCandidate::resourceId)
                .containsExactly(FIRST_RESOURCE_ID);

        insertResource(
                EARLIER_RESOURCE_ID,
                ACTIVE_ROLE_ID,
                "늦게 추가된 앞쪽 자료",
                "https://example.edu/earlier"
        );

        assertThat(outboxPort.findReconciliationCandidates(FIRST_RESOURCE_ID, 10))
                .extracting(WatchMonitorCandidate::resourceId)
                .containsExactly(SECOND_RESOURCE_ID);
        assertThat(outboxPort.findReconciliationCandidates(null, 10))
                .extracting(WatchMonitorCandidate::resourceId)
                .containsExactly(EARLIER_RESOURCE_ID, FIRST_RESOURCE_ID, SECOND_RESOURCE_ID);
    }

    @DisplayName("reconciliation page 크기는 양수여야 한다")
    @Test
    void rejectsNonPositiveReconciliationPageLimit() {
        assertThatThrownBy(() -> outboxPort.findReconciliationCandidates(null, 0))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("WATCH reconciliation page limit은 1 이상이어야 합니다");
    }

    private WatchMonitorChange activeChange(UUID eventId, UUID resourceId, String targetUrl) {
        return new WatchMonitorChange(
                eventId,
                resourceId,
                reference(resourceId),
                WatchMonitoringState.ACTIVE,
                targetUrl,
                OCCURRED_AT
        );
    }

    private WatchMonitorDelivery deliveryFor(List<WatchMonitorDelivery> deliveries, UUID resourceId) {
        return deliveries.stream()
                .filter(delivery -> delivery.resourceId().equals(resourceId))
                .findFirst()
                .orElseThrow();
    }

    private String storedStatus(long sourceRevision) {
        return jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM watch_monitor_outbox WHERE id = ?",
                String.class,
                sourceRevision
        );
    }

    private long outboxCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watch_monitor_outbox", Long.class);
        return count == null ? 0L : count;
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

    private void seedActiveWorkspace() {
        insertTeam(ACTIVE_TEAM_ID, "WATCH 활성 팀");
        insertSeason(ACTIVE_SEASON_ID, ACTIVE_TEAM_ID, "활성 시즌", null);
        insertRole(ACTIVE_ROLE_ID, ACTIVE_TEAM_ID, ACTIVE_SEASON_ID, "활성 운영 담당");
        insertResource(
                FIRST_RESOURCE_ID,
                ACTIVE_ROLE_ID,
                "첫 번째 자료",
                "https://example.com/first"
        );
        insertResource(
                SECOND_RESOURCE_ID,
                ACTIVE_ROLE_ID,
                "두 번째 자료",
                "https://example.org/second"
        );
    }

    private void seedEndedWorkspace() {
        insertTeam(ENDED_TEAM_ID, "WATCH 종료 팀");
        insertSeason(
                ENDED_SEASON_ID,
                ENDED_TEAM_ID,
                "종료 시즌",
                LocalDateTime.of(2026, 7, 31, 12, 0)
        );
        insertRole(ENDED_ROLE_ID, ENDED_TEAM_ID, ENDED_SEASON_ID, "종료 운영 담당");
        insertResource(
                ENDED_RESOURCE_ID,
                ENDED_ROLE_ID,
                "종료 시즌 자료",
                "https://example.net/ended"
        );
    }

    private void insertTeam(UUID teamId, String name) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                teamId.toString(),
                name,
                "a".repeat(64)
        );
    }

    private void insertSeason(UUID seasonId, UUID teamId, String name, LocalDateTime endedAt) {
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date, ended_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                seasonId.toString(),
                teamId.toString(),
                name,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31),
                endedAt
        );
    }

    private void insertRole(UUID roleId, UUID teamId, UUID seasonId, String name) {
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                roleId.toString(),
                teamId.toString(),
                seasonId.toString(),
                name,
                "WATCH 자료를 관리합니다"
        );
    }

    private void insertResource(UUID resourceId, UUID roleId, String title, String url) {
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, created_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                resourceId.toString(),
                roleId.toString(),
                title,
                url,
                LocalDateTime.of(2026, 8, 1, 11, 0)
        );
    }

    private String reference(UUID resourceId) {
        return "baton-manager:pilot:role-resource:" + resourceId;
    }

    private record StoredPayload(
            long sourceRevision,
            UUID eventId,
            String monitoringState,
            String targetUrl,
            LocalDateTime occurredAt
    ) {
    }
}
