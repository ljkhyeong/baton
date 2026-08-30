package com.personal.baton.application.watch;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxResult;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WatchHealthEventInboxPersistenceTest {

    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000001711");
    private static final UUID RESOURCE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000001712");
    private static final UUID ATTEMPT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000001713");
    private static final Instant CHANGED_AT =
            Instant.parse("2026-08-02T03:04:05.123456789Z");
    private static final Instant FIRST_ACCEPTED_AT =
            Instant.parse("2026-08-02T04:05:06.654321Z");
    private static final Instant LATER_ACCEPTED_AT =
            Instant.parse("2026-08-02T04:06:07.123456Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_watch_health_event_inbox")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WatchHealthEventInboxPort inboxPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM watch_health_event_inbox");
    }

    @DisplayName("동일한 WATCH event를 재수신하면 나노초 envelope와 최초 접수 시각을 그대로 재생한다")
    @Test
    void exactlyReplaysStoredEnvelopeAndFirstAcceptedAt() {
        WatchHealthChangedEvent event = baseEvent();

        WatchHealthEventInboxResult first = inboxPort.accept(event, FIRST_ACCEPTED_AT);
        WatchHealthEventInboxResult replay = inboxPort.accept(event, LATER_ACCEPTED_AT);

        assertThat(first.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(replay.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(first.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        assertThat(replay.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT BIN_TO_UUID(resource_id)
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                String.class,
                EVENT_ID.toString()
        )).isEqualTo(RESOURCE_ID.toString());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT changed_at
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                LocalDateTime.class,
                EVENT_ID.toString()
        )).isEqualTo(LocalDateTime.of(2026, 8, 2, 3, 4, 5, 123_456_000));
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT changed_at_nano_remainder
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                Integer.class,
                EVENT_ID.toString()
        )).isEqualTo(789);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT OCTET_LENGTH(payload_fingerprint)
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                Integer.class,
                EVENT_ID.toString()
        )).isEqualTo(32);
    }

    @DisplayName("MySQL DATETIME의 양 끝 changedAt도 나노초까지 저장하고 재생한다")
    @ParameterizedTest(name = "changedAt={0}")
    @ValueSource(strings = {
            "1000-01-01T00:00:00Z",
            "9999-12-31T23:59:59.999999999Z"
    })
    void persistsChangedAtStorageBoundaries(String changedAtValue) {
        WatchHealthChangedEvent event = event(
                17L,
                ATTEMPT_ID,
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                Instant.parse(changedAtValue),
                resourceReference(RESOURCE_ID)
        );

        WatchHealthEventInboxResult first = inboxPort.accept(event, FIRST_ACCEPTED_AT);
        WatchHealthEventInboxResult replay = inboxPort.accept(event, LATER_ACCEPTED_AT);

        assertThat(first.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(replay.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(replay.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
    }

    @DisplayName("attemptId가 없는 WATCH event도 신규 저장과 정확 replay를 지원한다")
    @Test
    void acceptsEventWithoutAttemptId() {
        WatchHealthChangedEvent event = event(
                17L,
                null,
                WatchResourceHealth.UNKNOWN,
                WatchResourceHealth.HEALTHY,
                CHANGED_AT,
                resourceReference(RESOURCE_ID)
        );

        WatchHealthEventInboxResult first = inboxPort.accept(event, FIRST_ACCEPTED_AT);
        WatchHealthEventInboxResult replay = inboxPort.accept(event, LATER_ACCEPTED_AT);

        assertThat(first.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(replay.status()).isEqualTo(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(replay.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox WHERE attempt_id IS NULL",
                Long.class
        )).isOne();
    }

    @DisplayName("높은 source revision 뒤에 도착한 낮은 revision의 다른 event도 버리지 않는다")
    @Test
    void preservesReorderedEventsAcrossSourceRevisions() {
        WatchHealthChangedEvent newerRevision = event(
                UUID.fromString("00000000-0000-4000-8000-000000001721"),
                18L,
                ATTEMPT_ID,
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                CHANGED_AT.plusSeconds(1),
                resourceReference(RESOURCE_ID)
        );
        WatchHealthChangedEvent lateOlderRevision = event(
                UUID.fromString("00000000-0000-4000-8000-000000001722"),
                17L,
                null,
                WatchResourceHealth.HEALTHY,
                WatchResourceHealth.DEGRADED,
                CHANGED_AT,
                resourceReference(RESOURCE_ID)
        );

        inboxPort.accept(newerRevision, FIRST_ACCEPTED_AT);
        inboxPort.accept(lateOlderRevision, LATER_ACCEPTED_AT);

        assertThat(jdbcTemplate.queryForList(
                "SELECT source_revision FROM watch_health_event_inbox ORDER BY source_revision",
                Long.class
        )).containsExactly(17L, 18L);
    }

    @DisplayName("같은 source revision의 서로 다른 health event를 모두 보존한다")
    @Test
    void preservesDistinctEventsAtSameSourceRevision() {
        WatchHealthChangedEvent becameHealthy = event(
                UUID.fromString("00000000-0000-4000-8000-000000001723"),
                17L,
                ATTEMPT_ID,
                WatchResourceHealth.UNKNOWN,
                WatchResourceHealth.HEALTHY,
                CHANGED_AT,
                resourceReference(RESOURCE_ID)
        );
        WatchHealthChangedEvent becameDegraded = event(
                UUID.fromString("00000000-0000-4000-8000-000000001724"),
                17L,
                null,
                WatchResourceHealth.HEALTHY,
                WatchResourceHealth.DEGRADED,
                CHANGED_AT.plusSeconds(1),
                resourceReference(RESOURCE_ID)
        );

        inboxPort.accept(becameHealthy, FIRST_ACCEPTED_AT);
        inboxPort.accept(becameDegraded, LATER_ACCEPTED_AT);

        assertThat(jdbcTemplate.queryForList(
                "SELECT current_health FROM watch_health_event_inbox ORDER BY changed_at",
                String.class
        )).containsExactly("HEALTHY", "DEGRADED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT source_revision FROM watch_health_event_inbox",
                Long.class
        )).containsOnly(17L).hasSize(2);
    }

    @DisplayName("변경 시각과 접수 순서가 엇갈린 서로 다른 event를 모두 보존한다")
    @Test
    void preservesEventsWhenChangedAtAndAcceptedAtOrdersDisagree() {
        WatchHealthChangedEvent laterChangeAcceptedFirst = event(
                UUID.fromString("00000000-0000-4000-8000-000000001725"),
                18L,
                ATTEMPT_ID,
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                CHANGED_AT.plusSeconds(1),
                resourceReference(RESOURCE_ID)
        );
        WatchHealthChangedEvent earlierChangeAcceptedLater = event(
                UUID.fromString("00000000-0000-4000-8000-000000001726"),
                17L,
                null,
                WatchResourceHealth.HEALTHY,
                WatchResourceHealth.DEGRADED,
                CHANGED_AT,
                resourceReference(RESOURCE_ID)
        );

        inboxPort.accept(laterChangeAcceptedFirst, FIRST_ACCEPTED_AT);
        inboxPort.accept(earlierChangeAcceptedLater, LATER_ACCEPTED_AT);

        assertThat(jdbcTemplate.queryForList(
                """
                SELECT BIN_TO_UUID(event_id)
                FROM watch_health_event_inbox
                ORDER BY accepted_at
                """,
                String.class
        )).containsExactly(
                laterChangeAcceptedFirst.eventId().toString(),
                earlierChangeAcceptedLater.eventId().toString()
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isEqualTo(2L);
    }

    @DisplayName("WATCH envelope fingerprint의 canonical framing을 안정되게 유지한다")
    @Test
    void keepsCanonicalFingerprintFramingStable() {
        inboxPort.accept(baseEvent(), FIRST_ACCEPTED_AT);

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT HEX(payload_fingerprint)
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                String.class,
                EVENT_ID.toString()
        )).isEqualTo(
                "E7F4DB17FFE0E0C10174A39F533BDBF6E7F1CAE1970030844C07F5042CF00A8A"
        );
    }

    @DisplayName("같은 eventId의 canonical envelope 필드가 하나라도 다르면 최초 행을 보존하고 충돌한다")
    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingEnvelopes")
    void conflictsWhenAnyCanonicalEnvelopeFieldChanges(
            String field,
            WatchHealthChangedEvent conflicting
    ) {
        WatchHealthChangedEvent original = baseEvent();
        inboxPort.accept(original, FIRST_ACCEPTED_AT);

        WatchHealthEventInboxResult result = inboxPort.accept(
                conflicting,
                LATER_ACCEPTED_AT
        );

        assertThat(field).isNotBlank();
        assertThat(result.status()).isEqualTo(WatchHealthEventInboxStatus.CONFLICT);
        assertThat(result.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT source_revision
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                Long.class,
                EVENT_ID.toString()
        )).isEqualTo(original.sourceRevision());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT accepted_at
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                """,
                LocalDateTime.class,
                EVENT_ID.toString()
        )).isEqualTo(LocalDateTime.of(2026, 8, 2, 4, 5, 6, 654_321_000));
    }

    @DisplayName("동일 event의 동시 재수신은 한 행과 하나의 최초 접수 시각으로 수렴한다")
    @Test
    void concurrentlyAcceptsExactReplayOnce() throws Exception {
        WatchHealthChangedEvent event = baseEvent();

        List<ConcurrentReceipt> receipts = acceptConcurrently(
                new ReceiptRequest(event, FIRST_ACCEPTED_AT),
                new ReceiptRequest(event, LATER_ACCEPTED_AT)
        );

        assertThat(receipts)
                .extracting(receipt -> receipt.result().status())
                .containsOnly(WatchHealthEventInboxStatus.ACCEPTED);
        assertThat(receipts)
                .extracting(receipt -> receipt.result().acceptedAt())
                .containsOnly(receipts.getFirst().result().acceptedAt());
        assertThat(receipts.getFirst().result().acceptedAt())
                .isIn(FIRST_ACCEPTED_AT, LATER_ACCEPTED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
    }

    @DisplayName("서로 다른 envelope의 동시 수신은 한 요청만 저장하고 다른 요청을 충돌로 판정한다")
    @Test
    void atomicallyConflictsConcurrentDifferentEnvelope() throws Exception {
        WatchHealthChangedEvent original = baseEvent();
        WatchHealthChangedEvent changed = mutate(
                original,
                event -> event(event.sourceRevision() + 1, event.attemptId(), event.previousHealth(),
                        event.currentHealth(), event.changedAt(), event.resourceReference())
        );

        List<ConcurrentReceipt> receipts = acceptConcurrently(
                new ReceiptRequest(original, FIRST_ACCEPTED_AT),
                new ReceiptRequest(changed, LATER_ACCEPTED_AT)
        );

        assertThat(receipts)
                .extracting(receipt -> receipt.result().status())
                .containsExactlyInAnyOrder(
                        WatchHealthEventInboxStatus.ACCEPTED,
                        WatchHealthEventInboxStatus.CONFLICT
                );
        ConcurrentReceipt accepted = receipts.stream()
                .filter(receipt -> receipt.result().status() == WatchHealthEventInboxStatus.ACCEPTED)
                .findFirst()
                .orElseThrow();
        assertThat(receipts)
                .extracting(receipt -> receipt.result().acceptedAt())
                .containsOnly(accepted.result().acceptedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_revision FROM watch_health_event_inbox",
                Long.class
        )).isEqualTo(accepted.event().sourceRevision());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
    }

    private Stream<Arguments> conflictingEnvelopes() {
        WatchHealthChangedEvent base = baseEvent();
        return Stream.of(
                Arguments.of("resourceReference", mutate(base, event -> event(
                        event.sourceRevision(),
                        event.attemptId(),
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt(),
                        "baton-manager:pilot:role-resource:00000000-0000-4000-8000-000000001799"
                ))),
                Arguments.of("sourceRevision", mutate(base, event -> event(
                        event.sourceRevision() + 1,
                        event.attemptId(),
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt(),
                        event.resourceReference()
                ))),
                Arguments.of("attemptId", mutate(base, event -> event(
                        event.sourceRevision(),
                        UUID.fromString("00000000-0000-4000-8000-000000001798"),
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt(),
                        event.resourceReference()
                ))),
                Arguments.of("attemptId presence", mutate(base, event -> event(
                        event.sourceRevision(),
                        null,
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt(),
                        event.resourceReference()
                ))),
                Arguments.of("previousHealth", mutate(base, event -> event(
                        event.sourceRevision(),
                        event.attemptId(),
                        WatchResourceHealth.HEALTHY,
                        event.currentHealth(),
                        event.changedAt(),
                        event.resourceReference()
                ))),
                Arguments.of("currentHealth", mutate(base, event -> event(
                        event.sourceRevision(),
                        event.attemptId(),
                        event.previousHealth(),
                        WatchResourceHealth.UNKNOWN,
                        event.changedAt(),
                        event.resourceReference()
                ))),
                Arguments.of("changedAt nanosecond", mutate(base, event -> event(
                        event.sourceRevision(),
                        event.attemptId(),
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt().plusNanos(1),
                        event.resourceReference()
                )))
        );
    }

    private List<ConcurrentReceipt> acceptConcurrently(
            ReceiptRequest first,
            ReceiptRequest second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentReceipt> firstFuture = executor.submit(
                    () -> acceptAfterBarrier(first, ready, start)
            );
            Future<ConcurrentReceipt> secondFuture = executor.submit(
                    () -> acceptAfterBarrier(second, ready, start)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(5, TimeUnit.SECONDS),
                    secondFuture.get(5, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ConcurrentReceipt acceptAfterBarrier(
            ReceiptRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return new ConcurrentReceipt(
                request.event(),
                inboxPort.accept(request.event(), request.acceptedAt())
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 수신 시작을 기다리지 못했습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 수신 대기가 중단됐습니다", exception);
        }
    }

    private WatchHealthChangedEvent baseEvent() {
        return event(
                17L,
                ATTEMPT_ID,
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                CHANGED_AT,
                resourceReference(RESOURCE_ID)
        );
    }

    private WatchHealthChangedEvent mutate(
            WatchHealthChangedEvent source,
            UnaryOperator<WatchHealthChangedEvent> mutation
    ) {
        return mutation.apply(source);
    }

    private WatchHealthChangedEvent event(
            long sourceRevision,
            UUID attemptId,
            WatchResourceHealth previousHealth,
            WatchResourceHealth currentHealth,
            Instant changedAt,
            String resourceReference
    ) {
        return event(
                EVENT_ID,
                sourceRevision,
                attemptId,
                previousHealth,
                currentHealth,
                changedAt,
                resourceReference
        );
    }

    private WatchHealthChangedEvent event(
            UUID eventId,
            long sourceRevision,
            UUID attemptId,
            WatchResourceHealth previousHealth,
            WatchResourceHealth currentHealth,
            Instant changedAt,
            String resourceReference
    ) {
        return new WatchHealthChangedEvent(
                eventId,
                WatchHealthChangedEvent.RESOURCE_HEALTH_CHANGED,
                UUID.fromString(resourceReference.substring(resourceReference.lastIndexOf(':') + 1)),
                resourceReference,
                sourceRevision,
                attemptId,
                previousHealth,
                currentHealth,
                changedAt
        );
    }

    private String resourceReference(UUID resourceId) {
        return "baton-manager:pilot:role-resource:" + resourceId;
    }

    private record ReceiptRequest(
            WatchHealthChangedEvent event,
            Instant acceptedAt
    ) {
    }

    private record ConcurrentReceipt(
            WatchHealthChangedEvent event,
            WatchHealthEventInboxResult result
    ) {
    }
}
