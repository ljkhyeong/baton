package com.personal.baton.bootstrap.observability;

import com.personal.baton.application.identity.EmailDeliveryEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntegrationDeliveryMetrics implements MeterBinder {

    private static final System.Logger LOGGER = System.getLogger(
            IntegrationDeliveryMetrics.class.getName()
    );

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile boolean refreshSuccessful;
    private volatile double lastSuccessfulRefreshEpochSeconds;

    public IntegrationDeliveryMetrics(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (EmailDeliveryEvent event : EmailDeliveryEvent.values()) {
            Gauge.builder("baton.email.delivery.receipts", this,
                            metrics -> metrics.snapshot.emailReceipts().getOrDefault(event, 0L))
                    .description("최근 24시간의 메일별 전달 결과 수; 재전송 중복 제외")
                    .tag("event", event.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry);
        }
        for (Integration integration : Integration.values()) {
            for (DeliveryStatus status : DeliveryStatus.values()) {
                Gauge.builder(
                                "baton.integration.delivery.items",
                                this,
                                metrics -> metrics.snapshot.delivery(integration).count(status)
                        )
                        .description("BATON 외부 연동 전달 상태별 아웃박스 항목 수")
                        .tag("integration", integration.tag())
                        .tag("status", status.tag())
                        .register(registry);
            }
            Gauge.builder(
                            "baton.integration.delivery.actionable.failed.items",
                            this,
                            metrics -> metrics.snapshot
                                    .delivery(integration)
                                    .actionableFailedItems()
                    )
                    .description("운영자 조치가 필요한 BATON 외부 연동 영구 실패 항목 수")
                    .tag("integration", integration.tag())
                    .register(registry);
            Gauge.builder(
                            "baton.integration.delivery.oldest.pending.age",
                            this,
                            metrics -> metrics.oldestPendingAgeSeconds(integration)
                    )
                    .description("BATON 외부 연동에서 가장 오래된 대기 항목의 경과 시간")
                    .baseUnit("seconds")
                    .tag("integration", integration.tag())
                    .register(registry);
            Gauge.builder(
                            "baton.integration.delivery.last.success.time",
                            this,
                            metrics -> metrics.snapshot
                                    .delivery(integration)
                                    .lastSuccessfulDeliveryEpochSeconds()
                    )
                    .description("BATON 외부 연동의 마지막 전달 성공 시각")
                    .baseUnit("seconds")
                    .tag("integration", integration.tag())
                    .register(registry);
            Gauge.builder(
                            "baton.integration.delivery.expired.processing.items",
                            this,
                            metrics -> metrics.snapshot
                                    .delivery(integration)
                                    .expiredProcessingItems()
                    )
                    .description("BATON 외부 연동에서 임대가 만료된 처리 중 항목 수")
                    .tag("integration", integration.tag())
                    .register(registry);
        }

        Gauge.builder(
                        "baton.integration.watch.inbox.items",
                        this,
                        metrics -> metrics.snapshot.watchInbox().storedItems()
                )
                .description("BATON이 저장한 WATCH 상태 변경 인박스 항목 수")
                .register(registry);
        Gauge.builder(
                        "baton.integration.watch.inbox.last.accepted.time",
                        this,
                        metrics -> metrics.snapshot.watchInbox().lastAcceptedEpochSeconds()
                )
                .description("BATON이 마지막 WATCH 상태 변경 이벤트를 접수한 시각")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(
                        "baton.integration.metrics.refresh.success",
                        this,
                        metrics -> metrics.refreshSuccessful ? 1 : 0
                )
                .description("BATON 외부 연동 운영 지표의 최근 갱신 성공 여부")
                .register(registry);
        Gauge.builder(
                        "baton.integration.metrics.last.successful.refresh.time",
                        this,
                        metrics -> metrics.lastSuccessfulRefreshEpochSeconds
                )
                .description("BATON 외부 연동 운영 지표의 마지막 정상 갱신 시각")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(
            fixedDelay = 30,
            initialDelay = 0,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            scheduler = "integrationMetricsTaskScheduler"
    )
    void refresh() {
        try {
            EnumMap<Integration, DeliverySnapshot> deliveries = new EnumMap<>(Integration.class);
            for (Integration integration : Integration.values()) {
                deliveries.put(integration, readDeliverySnapshot(integration));
            }
            WatchInboxSnapshot watchInbox = readWatchInboxSnapshot();
            Instant refreshedAt = clock.instant();

            snapshot = new Snapshot(Map.copyOf(deliveries), watchInbox, readEmailReceipts(refreshedAt));
            lastSuccessfulRefreshEpochSeconds = epochSeconds(refreshedAt);
            refreshSuccessful = true;
        } catch (DataAccessException exception) {
            refreshSuccessful = false;
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "BATON 외부 연동 운영 지표를 갱신하지 못했습니다.",
                    exception
            );
        }
    }

    private Map<EmailDeliveryEvent, Long> readEmailReceipts(Instant now) {
        EnumMap<EmailDeliveryEvent, Long> receipts = new EnumMap<>(EmailDeliveryEvent.class);
        jdbcTemplate.query("""
                SELECT event, COUNT(*) AS count FROM email_delivery_receipts
                WHERE occurred_at > ? AND occurred_at <= ? GROUP BY event
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        receipts.put(EmailDeliveryEvent.valueOf(row.getString("event")), row.getLong("count")),
                LocalDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return Map.copyOf(receipts);
    }

    private DeliverySnapshot readDeliverySnapshot(Integration integration) {
        String unresolvedFailure = integration == Integration.CALENDAR_METADATA ? """
                AND NOT EXISTS (
                    SELECT 1 FROM calendar_season_metadata_outbox successor
                    WHERE successor.season_id = delivery.season_id
                      AND successor.id > delivery.id
                      AND successor.delivery_status = 'DELIVERED'
                )
                """ : "";
        String sql = """
                SELECT
                    COALESCE(SUM(delivery_status = 'PENDING'), 0) AS pending_count,
                    COALESCE(SUM(delivery_status = 'PROCESSING'), 0) AS processing_count,
                    COALESCE(SUM(delivery_status = 'FAILED'), 0) AS failed_count,
                    COALESCE(SUM(
                        delivery_status = 'FAILED'
                        AND (? = '' OR last_error_code IS NULL OR last_error_code <> ?)
                        %s
                    ), 0) AS actionable_failed_count,
                    MIN(CASE
                        WHEN delivery_status = 'PENDING' THEN %s
                        ELSE NULL
                    END) AS oldest_pending_at,
                    MAX(CASE
                        WHEN delivery_status = 'DELIVERED' THEN completed_at
                        ELSE NULL
                    END) AS last_success_at,
                    COALESCE(SUM(
                        delivery_status = 'PROCESSING' AND lease_expires_at <= ?
                    ), 0) AS expired_processing_count
                FROM %s delivery
                """.formatted(unresolvedFailure, integration.pendingSinceColumn(), integration.tableName());
        return jdbcTemplate.queryForObject(
                sql,
                this::deliverySnapshot,
                integration.nonActionableFailureCode(),
                integration.nonActionableFailureCode(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
    }

    private DeliverySnapshot deliverySnapshot(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DeliverySnapshot(
                resultSet.getLong("pending_count"),
                resultSet.getLong("processing_count"),
                resultSet.getLong("failed_count"),
                resultSet.getLong("actionable_failed_count"),
                instantOrNull(resultSet, "oldest_pending_at"),
                epochSeconds(instantOrNull(resultSet, "last_success_at")),
                resultSet.getLong("expired_processing_count")
        );
    }

    private WatchInboxSnapshot readWatchInboxSnapshot() {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) AS stored_items,
                    MAX(accepted_at) AS last_accepted_at
                FROM watch_health_event_inbox
                """,
                (resultSet, rowNumber) -> new WatchInboxSnapshot(
                        resultSet.getLong("stored_items"),
                        epochSeconds(instantOrNull(resultSet, "last_accepted_at"))
                )
        );
    }

    private double oldestPendingAgeSeconds(Integration integration) {
        Instant oldestPendingAt = snapshot.delivery(integration).oldestPendingAt();
        if (oldestPendingAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldestPendingAt, clock.instant()).toSeconds());
    }

    private Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private double epochSeconds(Instant instant) {
        if (instant == null) {
            return 0;
        }
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000d;
    }

    private enum Integration {
        CALENDAR("calendar", "calendar_snapshot_outbox", "occurred_at", ""),
        CALENDAR_METADATA("calendar_metadata", "calendar_season_metadata_outbox", "occurred_at", ""),
        WATCH("watch", "watch_monitor_outbox", "occurred_at", ""),
        BRIEF("brief", "brief_continuity_outbox", "occurred_at", ""),
        EMAIL(
                "email",
                "email_verification_delivery_outbox",
                "created_at",
                "VERIFICATION_TOKEN_EXPIRED"
        );

        private final String tag;
        private final String tableName;
        private final String pendingSinceColumn;
        private final String nonActionableFailureCode;

        Integration(
                String tag,
                String tableName,
                String pendingSinceColumn,
                String nonActionableFailureCode
        ) {
            this.tag = tag;
            this.tableName = tableName;
            this.pendingSinceColumn = pendingSinceColumn;
            this.nonActionableFailureCode = nonActionableFailureCode;
        }

        private String tag() {
            return tag;
        }

        private String tableName() {
            return tableName;
        }

        private String pendingSinceColumn() {
            return pendingSinceColumn;
        }

        private String nonActionableFailureCode() {
            return nonActionableFailureCode;
        }
    }

    private enum DeliveryStatus {
        PENDING("pending"),
        PROCESSING("processing"),
        FAILED("failed");

        private final String tag;

        DeliveryStatus(String tag) {
            this.tag = tag;
        }

        private String tag() {
            return tag;
        }
    }

    private record DeliverySnapshot(
            long pendingItems,
            long processingItems,
            long failedItems,
            long actionableFailedItems,
            Instant oldestPendingAt,
            double lastSuccessfulDeliveryEpochSeconds,
            long expiredProcessingItems
    ) {

        private static final DeliverySnapshot EMPTY = new DeliverySnapshot(0, 0, 0, 0, null, 0, 0);

        private long count(DeliveryStatus status) {
            return switch (status) {
                case PENDING -> pendingItems;
                case PROCESSING -> processingItems;
                case FAILED -> failedItems;
            };
        }
    }

    private record WatchInboxSnapshot(long storedItems, double lastAcceptedEpochSeconds) {

        private static WatchInboxSnapshot empty() {
            return new WatchInboxSnapshot(0, 0);
        }
    }

    private record Snapshot(
            Map<Integration, DeliverySnapshot> deliveries,
            WatchInboxSnapshot watchInbox,
            Map<EmailDeliveryEvent, Long> emailReceipts
    ) {

        private DeliverySnapshot delivery(Integration integration) {
            return deliveries.getOrDefault(integration, DeliverySnapshot.EMPTY);
        }

        private static Snapshot empty() {
            return new Snapshot(Map.of(), WatchInboxSnapshot.empty(), Map.of());
        }
    }
}
