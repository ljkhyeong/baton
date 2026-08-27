package com.personal.baton.application.watch;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class WatchHealthEventInboxMigrationTest {

    private static final String OUTBOX_EVENT_ID =
            "00000000-0000-0000-0000-000000001701";
    private static final String RESOURCE_ID =
            "00000000-0000-0000-0000-000000001702";
    private static final String INBOX_EVENT_ID =
            "00000000-0000-0000-0000-000000001703";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_watch_health_event_inbox_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V17은 V16 outbox를 보존하고 독립적인 WATCH health event inbox를 추가한다")
    @Test
    void preservesV16OutboxAndAddsConstrainedInbox() {
        migrateTo("16");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 2, 1, 2, 3, 456_000_000);
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
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?, ?)
                """,
                OUTBOX_EVENT_ID,
                RESOURCE_ID,
                resourceReference(),
                "https://example.com/operations",
                occurredAt,
                occurredAt
        );

        migrateTo("17");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_monitor_outbox WHERE event_id = UUID_TO_BIN(?)",
                Long.class,
                OUTBOX_EVENT_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE()
                AND table_name = 'watch_health_event_inbox'
                """,
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForList(
                """
                SELECT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                AND table_name = 'watch_health_event_inbox'
                """,
                String.class
        )).contains("PRIMARY", "idx_watch_health_event_inbox_resource_revision");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                AND table_name = 'watch_health_event_inbox'
                AND column_name = 'payload_fingerprint'
                """,
                Long.class
        )).isEqualTo(32L);

        insertInbox(jdbcTemplate, 999);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_health_event_inbox",
                Long.class
        )).isOne();
        assertThatThrownBy(() -> insertInbox(jdbcTemplate, 1_000))
                .isInstanceOf(DataAccessException.class);
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

    private void insertInbox(JdbcTemplate jdbcTemplate, int nanoRemainder) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_health_event_inbox (
                    event_id,
                    resource_id,
                    event_type,
                    resource_reference,
                    source_revision,
                    attempt_id,
                    previous_health,
                    current_health,
                    changed_at,
                    changed_at_nano_remainder,
                    payload_fingerprint,
                    accepted_at
                ) VALUES (
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    'RESOURCE_HEALTH_CHANGED',
                    ?,
                    17,
                    NULL,
                    'DEGRADED',
                    'BROKEN',
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                nanoRemainder == 999
                        ? INBOX_EVENT_ID
                        : "00000000-0000-0000-0000-000000001704",
                RESOURCE_ID,
                resourceReference(),
                LocalDateTime.of(2026, 8, 2, 3, 4, 5, 123_456_000),
                nanoRemainder,
                new byte[32],
                LocalDateTime.of(2026, 8, 2, 4, 5, 6, 654_321_000)
        );
    }

    private String resourceReference() {
        return "baton-manager:pilot:role-resource:" + RESOURCE_ID;
    }
}
