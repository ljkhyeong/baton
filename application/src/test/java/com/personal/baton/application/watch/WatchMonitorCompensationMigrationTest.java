package com.personal.baton.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class WatchMonitorCompensationMigrationTest {

    private static final String FIRST_RESOURCE_ID =
            "00000000-0000-0000-0000-000000001801";
    private static final String SECOND_RESOURCE_ID =
            "00000000-0000-0000-0000-000000001802";
    private static final LocalDateTime FAILED_AT =
            LocalDateTime.of(2026, 8, 8, 3, 4, 5, 123_456_000);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_watch_compensation_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V18은 기존 INACTIVE를 추측하지 않고 새 invalid-target 보상을 위한 제약을 추가한다")
    @Test
    void preservesLegacyInactiveSnapshotsAndConstrainsNewCompensation() {
        migrateTo("17");
        JdbcTemplate jdbcTemplate = jdbcTemplate();

        insertRejectedActive(jdbcTemplate, 101, FIRST_RESOURCE_ID, "https://example.com/rejected");
        insertInactive(jdbcTemplate, 102, FIRST_RESOURCE_ID, FAILED_AT);

        insertRejectedActive(jdbcTemplate, 201, SECOND_RESOURCE_ID, "https://example.org/rejected");
        insertPendingActive(
                jdbcTemplate,
                202,
                SECOND_RESOURCE_ID,
                "https://example.org/intervening"
        );
        insertInactive(jdbcTemplate, 203, SECOND_RESOURCE_ID, FAILED_AT);

        migrateTo("18");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT compensation_for_id FROM watch_monitor_outbox WHERE id = 102",
                Long.class
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT compensation_for_id FROM watch_monitor_outbox WHERE id = 203",
                Long.class
        )).isNull();
        assertThat(jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'watch_monitor_outbox'",
                String.class
        )).contains("uk_watch_monitor_outbox_compensation_source");
        assertThat(jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() "
                        + "AND table_name = 'watch_monitor_outbox'",
                String.class
        )).containsExactly("fk_watch_monitor_outbox_compensation_source");

        insertCompensation(
                jdbcTemplate,
                301,
                FIRST_RESOURCE_ID,
                101
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT compensation_for_id FROM watch_monitor_outbox WHERE id = 301",
                Long.class
        )).isEqualTo(101L);
        assertThatThrownBy(() -> insertCompensation(
                jdbcTemplate,
                304,
                FIRST_RESOURCE_ID,
                101
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertCompensation(
                jdbcTemplate,
                302,
                FIRST_RESOURCE_ID,
                999
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertActiveCompensation(
                jdbcTemplate,
                303,
                SECOND_RESOURCE_ID,
                201
        )).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '18'",
                Boolean.class
        )).isTrue();
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
    }

    private void insertRejectedActive(
            JdbcTemplate jdbcTemplate,
            long id,
            String resourceId,
            String targetUrl
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    id,
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    occurred_at,
                    delivery_status,
                    attempt_count,
                    available_at,
                    completed_at,
                    last_error_code
                ) VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?,
                    'FAILED', 1, ?, ?, 'INVALID_TARGET_URL')
                """,
                id,
                eventId(id),
                resourceId,
                resourceReference(resourceId),
                targetUrl,
                FAILED_AT.minusSeconds(1),
                FAILED_AT.minusSeconds(1),
                FAILED_AT
        );
    }

    private void insertPendingActive(
            JdbcTemplate jdbcTemplate,
            long id,
            String resourceId,
            String targetUrl
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    id,
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    occurred_at,
                    available_at
                ) VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?, ?)
                """,
                id,
                eventId(id),
                resourceId,
                resourceReference(resourceId),
                targetUrl,
                FAILED_AT,
                FAILED_AT
        );
    }

    private void insertInactive(
            JdbcTemplate jdbcTemplate,
            long id,
            String resourceId,
            LocalDateTime occurredAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    id,
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    occurred_at,
                    available_at
                ) VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'INACTIVE', NULL, ?, ?)
                """,
                id,
                eventId(id),
                resourceId,
                resourceReference(resourceId),
                occurredAt,
                occurredAt
        );
    }

    private void insertCompensation(
            JdbcTemplate jdbcTemplate,
            long id,
            String resourceId,
            long compensationForId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    id,
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    compensation_for_id,
                    occurred_at,
                    available_at
                ) VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'INACTIVE', NULL, ?, ?, ?)
                """,
                id,
                eventId(id),
                resourceId,
                resourceReference(resourceId),
                compensationForId,
                FAILED_AT.plusSeconds(1),
                FAILED_AT.plusSeconds(1)
        );
    }

    private void insertActiveCompensation(
            JdbcTemplate jdbcTemplate,
            long id,
            String resourceId,
            long compensationForId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    id,
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    compensation_for_id,
                    occurred_at,
                    available_at
                ) VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?, ?, ?)
                """,
                id,
                eventId(id),
                resourceId,
                resourceReference(resourceId),
                "https://example.org/invalid-compensation",
                compensationForId,
                FAILED_AT.plusSeconds(1),
                FAILED_AT.plusSeconds(1)
        );
    }

    private String eventId(long id) {
        return String.format("00000000-0000-0000-0000-%012d", id);
    }

    private String resourceReference(String resourceId) {
        return "baton-manager:pilot:role-resource:" + resourceId;
    }
}
