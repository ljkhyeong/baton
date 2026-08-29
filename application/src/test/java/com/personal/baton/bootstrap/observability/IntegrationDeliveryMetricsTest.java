package com.personal.baton.bootstrap.observability;

import com.personal.baton.BatonApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {BatonApplication.class, IntegrationDeliveryMetricsTest.FixedClockConfig.class},
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H"
        }
)
class IntegrationDeliveryMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-27T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_integration_metrics")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationDeliveryMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM email_verification_delivery_outbox");
        jdbcTemplate.update("DELETE FROM account_identities");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM brief_continuity_outbox");
        jdbcTemplate.update("DELETE FROM watch_health_event_inbox");
        jdbcTemplate.update("DELETE FROM watch_monitor_outbox WHERE compensation_for_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM watch_monitor_outbox");
        jdbcTemplate.update("DELETE FROM calendar_snapshot_outbox");
    }

    @DisplayName("외부 연동 전달 상태와 조치 대상 실패를 공통 운영 지표로 노출한다")
    @Test
    void exposesIntegrationDeliveryAndInboxMetrics() {
        insertCalendar("PENDING", NOW.minusSeconds(120));
        insertCalendar("PROCESSING", NOW.minusSeconds(90));
        insertCalendar("FAILED", NOW.minusSeconds(60));
        insertCalendar("DELIVERED", NOW.minusSeconds(30));
        insertWatch("PENDING", NOW.minusSeconds(240));
        insertWatch("PROCESSING", NOW.minusSeconds(210));
        insertWatch("FAILED", NOW.minusSeconds(180));
        insertWatch("DELIVERED", NOW.minusSeconds(45));
        insertBrief("PENDING", NOW.minusSeconds(360));
        insertBrief("PROCESSING", NOW.minusSeconds(330));
        insertBrief("FAILED", NOW.minusSeconds(300));
        insertBrief("DELIVERED", NOW.minusSeconds(75));
        insertEmail("PENDING", NOW.minusSeconds(480), null);
        insertEmail("PROCESSING", NOW.minusSeconds(450), null);
        insertEmail("FAILED", NOW.minusSeconds(420), "SMTP_DELIVERY_FAILED");
        insertEmail("FAILED", NOW.minusSeconds(390), "VERIFICATION_TOKEN_EXPIRED");
        insertEmail("SUPERSEDED", NOW.minusSeconds(360), null);
        insertEmail("DELIVERED", NOW.minusSeconds(105), null);
        insertWatchInbox(NOW.minusSeconds(15));

        metrics.refresh();

        assertThat(deliveryItems(registry, "calendar", "pending")).isEqualTo(1);
        assertThat(deliveryItems(registry, "calendar", "processing")).isEqualTo(1);
        assertThat(deliveryItems(registry, "calendar", "failed")).isEqualTo(1);
        assertThat(deliveryItems(registry, "watch", "pending")).isEqualTo(1);
        assertThat(deliveryItems(registry, "watch", "processing")).isEqualTo(1);
        assertThat(deliveryItems(registry, "watch", "failed")).isEqualTo(1);
        assertThat(deliveryItems(registry, "brief", "pending")).isEqualTo(1);
        assertThat(deliveryItems(registry, "brief", "processing")).isEqualTo(1);
        assertThat(deliveryItems(registry, "brief", "failed")).isEqualTo(1);
        assertThat(deliveryItems(registry, "email", "pending")).isEqualTo(1);
        assertThat(deliveryItems(registry, "email", "processing")).isEqualTo(1);
        assertThat(deliveryItems(registry, "email", "failed")).isEqualTo(2);
        assertThat(actionableFailedItems(registry, "calendar")).isEqualTo(1);
        assertThat(actionableFailedItems(registry, "watch")).isEqualTo(1);
        assertThat(actionableFailedItems(registry, "brief")).isEqualTo(1);
        assertThat(actionableFailedItems(registry, "email")).isEqualTo(1);
        assertThat(gauge(registry, "baton.integration.delivery.oldest.pending.age", "calendar"))
                .isEqualTo(120);
        assertThat(gauge(registry, "baton.integration.delivery.oldest.pending.age", "watch"))
                .isEqualTo(240);
        assertThat(gauge(registry, "baton.integration.delivery.oldest.pending.age", "brief"))
                .isEqualTo(360);
        assertThat(gauge(registry, "baton.integration.delivery.oldest.pending.age", "email"))
                .isEqualTo(480);
        assertThat(gauge(registry, "baton.integration.delivery.last.success.time", "calendar"))
                .isEqualTo(NOW.minusSeconds(30).getEpochSecond());
        assertThat(gauge(registry, "baton.integration.delivery.last.success.time", "watch"))
                .isEqualTo(NOW.minusSeconds(45).getEpochSecond());
        assertThat(gauge(registry, "baton.integration.delivery.last.success.time", "brief"))
                .isEqualTo(NOW.minusSeconds(75).getEpochSecond());
        assertThat(gauge(registry, "baton.integration.delivery.last.success.time", "email"))
                .isEqualTo(NOW.minusSeconds(105).getEpochSecond());
        assertThat(gauge(
                registry,
                "baton.integration.delivery.expired.processing.items",
                "calendar"
        )).isZero();
        assertThat(gauge(
                registry,
                "baton.integration.delivery.expired.processing.items",
                "watch"
        )).isEqualTo(1);
        assertThat(gauge(
                registry,
                "baton.integration.delivery.expired.processing.items",
                "brief"
        )).isEqualTo(1);
        assertThat(gauge(
                registry,
                "baton.integration.delivery.expired.processing.items",
                "email"
        )).isEqualTo(1);
        assertThat(registry.get("baton.integration.watch.inbox.items").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("baton.integration.watch.inbox.last.accepted.time").gauge().value())
                .isEqualTo(NOW.minusSeconds(15).getEpochSecond());
        assertThat(registry.get("baton.integration.metrics.refresh.success").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("baton.integration.metrics.last.successful.refresh.time")
                .gauge().value()).isEqualTo(NOW.getEpochSecond());
    }

    private double deliveryItems(
            MeterRegistry registry,
            String integration,
            String status
    ) {
        return registry.get("baton.integration.delivery.items")
                .tags("integration", integration, "status", status)
                .gauge()
                .value();
    }

    private double actionableFailedItems(MeterRegistry registry, String integration) {
        return gauge(
                registry,
                "baton.integration.delivery.actionable.failed.items",
                integration
        );
    }

    private double gauge(MeterRegistry registry, String name, String integration) {
        return registry.get(name)
                .tag("integration", integration)
                .gauge()
                .value();
    }

    private void insertCalendar(String deliveryStatus, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO calendar_snapshot_outbox (
                    event_id,
                    source_item_id,
                    season_id,
                    occurred_at,
                    calendar_status,
                    summary,
                    time_type,
                    start_date,
                    end_date,
                    source_updated_at,
                    available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                    'ACTIVE', '운영 지표 테스트', 'ALL_DAY', ?, ?, ?, ?
                )
                """,
                eventId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                utc(occurredAt),
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 28),
                utc(occurredAt),
                utc(occurredAt)
        );
        updateCalendarStatus(eventId, deliveryStatus, occurredAt);
    }

    private void updateCalendarStatus(UUID eventId, String deliveryStatus, Instant occurredAt) {
        switch (deliveryStatus) {
            case "PENDING" -> {
            }
            case "PROCESSING" -> jdbcTemplate.update(
                    """
                    UPDATE calendar_snapshot_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    UUID.randomUUID().toString(),
                    utc(NOW.plusSeconds(30)),
                    eventId.toString()
            );
            case "FAILED" -> jdbcTemplate.update(
                    """
                    UPDATE calendar_snapshot_outbox
                    SET delivery_status = 'FAILED',
                        attempt_count = 1,
                        completed_at = ?,
                        last_error_code = 'HTTP_422'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt.plusSeconds(10)),
                    eventId.toString()
            );
            case "DELIVERED" -> jdbcTemplate.update(
                    """
                    UPDATE calendar_snapshot_outbox
                    SET delivery_status = 'DELIVERED',
                        attempt_count = 1,
                        completed_at = ?,
                        result_code = 'APPLIED'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt),
                    eventId.toString()
            );
            default -> throw new IllegalArgumentException("지원하지 않는 CAL 전달 상태입니다");
        }
    }

    private void insertWatch(String deliveryStatus, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    occurred_at,
                    available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?, ?
                )
                """,
                eventId.toString(),
                UUID.randomUUID().toString(),
                "baton-manager:metrics:role-resource:" + UUID.randomUUID(),
                "https://example.com/metrics",
                utc(occurredAt),
                utc(occurredAt)
        );
        switch (deliveryStatus) {
            case "PENDING" -> {
            }
            case "PROCESSING" -> jdbcTemplate.update(
                    """
                    UPDATE watch_monitor_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    UUID.randomUUID().toString(),
                    utc(NOW.minusSeconds(1)),
                    eventId.toString()
            );
            case "FAILED" -> jdbcTemplate.update(
                    """
                    UPDATE watch_monitor_outbox
                    SET delivery_status = 'FAILED',
                        attempt_count = 1,
                        completed_at = ?,
                        last_error_code = 'HTTP_422'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt.plusSeconds(10)),
                    eventId.toString()
            );
            case "DELIVERED" -> jdbcTemplate.update(
                    """
                    UPDATE watch_monitor_outbox
                    SET delivery_status = 'DELIVERED',
                        attempt_count = 1,
                        completed_at = ?,
                        result_code = 'APPLIED'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt),
                    eventId.toString()
            );
            default -> throw new IllegalArgumentException("지원하지 않는 WATCH 전달 상태입니다");
        }
    }

    private void insertBrief(String deliveryStatus, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id,
                    signal_id,
                    workspace_id,
                    season_id,
                    event_type,
                    event_version,
                    source_severity,
                    source_reference,
                    aggregate_revision,
                    occurred_at,
                    event_state,
                    available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    'ROLE_UNASSIGNED', 2, 'WARNING', ?, 1, ?, 'ACTIVE', ?
                )
                """,
                eventId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "baton-continuity:metrics:" + UUID.randomUUID(),
                utc(occurredAt),
                utc(occurredAt)
        );
        updateBriefStatus(eventId, deliveryStatus, occurredAt);
    }

    private void updateBriefStatus(UUID eventId, String deliveryStatus, Instant occurredAt) {
        switch (deliveryStatus) {
            case "PENDING" -> {
            }
            case "PROCESSING" -> jdbcTemplate.update(
                    """
                    UPDATE brief_continuity_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    UUID.randomUUID().toString(),
                    utc(NOW.minusSeconds(1)),
                    eventId.toString()
            );
            case "FAILED" -> jdbcTemplate.update(
                    """
                    UPDATE brief_continuity_outbox
                    SET delivery_status = 'FAILED',
                        attempt_count = 1,
                        completed_at = ?,
                        last_error_code = 'HTTP_422'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt.plusSeconds(10)),
                    eventId.toString()
            );
            case "DELIVERED" -> jdbcTemplate.update(
                    """
                    UPDATE brief_continuity_outbox
                    SET delivery_status = 'DELIVERED',
                        attempt_count = 1,
                        completed_at = ?,
                        result_code = 'APPLIED'
                    WHERE event_id = UUID_TO_BIN(?)
                    """,
                    utc(occurredAt),
                    eventId.toString()
            );
            default -> throw new IllegalArgumentException("지원하지 않는 BRIEF 전달 상태입니다");
        }
    }

    private void insertEmail(String deliveryStatus, Instant createdAt, String errorCode) {
        UUID accountId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        Instant expiresAt = "VERIFICATION_TOKEN_EXPIRED".equals(errorCode)
                ? createdAt.plusSeconds(30)
                : createdAt.plusSeconds(900);
        jdbcTemplate.update(
                """
                INSERT INTO accounts (id, display_name, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), '운영 지표 테스트', ?, ?)
                """,
                accountId.toString(),
                utc(createdAt),
                utc(createdAt)
        );
        String email = identityId + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO account_identities (
                    id, account_id, provider, provider_subject,
                    email_snapshot, email_verified, created_at
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'LOCAL_EMAIL', ?, ?, FALSE, ?)
                """,
                identityId.toString(),
                accountId.toString(),
                email,
                email,
                utc(createdAt)
        );
        jdbcTemplate.update(
                """
                INSERT INTO email_verification_delivery_outbox (
                    identity_id,
                    payload_ciphertext,
                    payload_nonce,
                    challenge_token_hash,
                    expires_at,
                    delivery_status,
                    available_at,
                    created_at
                ) VALUES (
                    UUID_TO_BIN(?), 'abcdefghijklmnop', 'abcdefghijklmnop',
                    REPEAT('a', 64), ?, 'PENDING', ?, ?
                )
                """,
                identityId.toString(),
                utc(expiresAt),
                utc(createdAt),
                utc(createdAt)
        );
        updateEmailStatus(identityId, deliveryStatus, createdAt, errorCode);
    }

    private void updateEmailStatus(
            UUID identityId,
            String deliveryStatus,
            Instant createdAt,
            String errorCode
    ) {
        switch (deliveryStatus) {
            case "PENDING" -> {
            }
            case "PROCESSING" -> jdbcTemplate.update(
                    """
                    UPDATE email_verification_delivery_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?
                    WHERE identity_id = UUID_TO_BIN(?)
                    """,
                    UUID.randomUUID().toString(),
                    utc(NOW.minusSeconds(1)),
                    identityId.toString()
            );
            case "FAILED" -> jdbcTemplate.update(
                    """
                    UPDATE email_verification_delivery_outbox
                    SET delivery_status = 'FAILED',
                        attempt_count = 1,
                        payload_ciphertext = NULL,
                        payload_nonce = NULL,
                        challenge_token_hash = NULL,
                        completed_at = ?,
                        last_error_code = ?
                    WHERE identity_id = UUID_TO_BIN(?)
                    """,
                    utc(createdAt.plusSeconds(10)),
                    errorCode,
                    identityId.toString()
            );
            case "SUPERSEDED", "DELIVERED" -> jdbcTemplate.update(
                    """
                    UPDATE email_verification_delivery_outbox
                    SET delivery_status = ?,
                        attempt_count = 1,
                        payload_ciphertext = NULL,
                        payload_nonce = NULL,
                        challenge_token_hash = NULL,
                        completed_at = ?
                    WHERE identity_id = UUID_TO_BIN(?)
                    """,
                    deliveryStatus,
                    utc(createdAt),
                    identityId.toString()
            );
            default -> throw new IllegalArgumentException("지원하지 않는 이메일 전달 상태입니다");
        }
    }

    private void insertWatchInbox(Instant acceptedAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO watch_health_event_inbox (
                    event_id,
                    resource_id,
                    event_type,
                    resource_reference,
                    source_revision,
                    previous_health,
                    current_health,
                    changed_at,
                    changed_at_nano_remainder,
                    payload_fingerprint,
                    accepted_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), 'RESOURCE_HEALTH_CHANGED', ?, 1,
                    'HEALTHY', 'BROKEN', ?, 0, UNHEX(REPEAT('01', 32)), ?
                )
                """,
                eventId.toString(),
                UUID.randomUUID().toString(),
                "baton-manager:metrics:role-resource:" + UUID.randomUUID(),
                utc(acceptedAt.minusSeconds(1)),
                utc(acceptedAt)
        );
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock integrationMetricsClock() {
            return CLOCK;
        }
    }
}
