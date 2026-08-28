package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
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
class BriefContinuityOutboxDeliveryMigrationTest {

    private static final String EVENT_ID = "00000000-0000-0000-0000-000000002301";
    private static final String SIGNAL_ID = "00000000-0000-0000-0000-000000002302";
    private static final String WORKSPACE_ID = "00000000-0000-0000-0000-000000002303";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000002304";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(
            2026,
            8,
            27,
            3,
            4,
            5,
            123_456_000
    );

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_brief_delivery_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V25는 기존 BRIEF 이벤트를 보존하고 전달 대기 상태로 이관한다")
    @Test
    void preservesExistingEventAndAddsDeliveryLifecycle() {
        migrateTo("24");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id, signal_id, workspace_id, season_id, event_type,
                    event_version, source_severity, source_reference,
                    aggregate_revision, occurred_at, event_state
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    'ROLE_UNASSIGNED', 2, 'CRITICAL', ?, 1, ?, 'ACTIVE'
                )
                """,
                EVENT_ID,
                SIGNAL_ID,
                WORKSPACE_ID,
                SEASON_ID,
                "baton-continuity:" + SIGNAL_ID,
                OCCURRED_AT
        );

        migrateTo("25");

        Map<String, Object> migrated = jdbcTemplate.queryForMap(
                """
                SELECT BIN_TO_UUID(event_id) AS event_id,
                       aggregate_revision, occurred_at, delivery_status,
                       attempt_count, available_at, lease_token,
                       lease_expires_at, completed_at, result_code, last_error_code
                FROM brief_continuity_outbox
                """
        );
        assertThat(migrated)
                .containsEntry("EVENT_ID", EVENT_ID)
                .containsEntry("AGGREGATE_REVISION", 1L)
                .containsEntry("OCCURRED_AT", OCCURRED_AT)
                .containsEntry("DELIVERY_STATUS", "PENDING")
                .containsEntry("ATTEMPT_COUNT", 0)
                .containsEntry("AVAILABLE_AT", OCCURRED_AT)
                .containsEntry("LEASE_TOKEN", null)
                .containsEntry("LEASE_EXPIRES_AT", null)
                .containsEntry("COMPLETED_AT", null)
                .containsEntry("RESULT_CODE", null)
                .containsEntry("LAST_ERROR_CODE", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE brief_continuity_outbox SET delivery_status = 'PROCESSING'"
        )).isInstanceOf(DataAccessException.class);
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
}
