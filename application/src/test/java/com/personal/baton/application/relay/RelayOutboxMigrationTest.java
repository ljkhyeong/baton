package com.personal.baton.application.relay;

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
class RelayOutboxMigrationTest {

    private static final String WATCH_EVENT_ID = "00000000-0000-0000-0000-000000001711";
    private static final String WATCH_RESOURCE_ID = "00000000-0000-0000-0000-000000001712";
    private static final String RELAY_EVENT_ID = "00000000-0000-0000-0000-000000001713";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_relay_outbox_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V17은 기존 WATCH 기록을 보존하고 외부 FK 없이 제약된 빈 RELAY outbox를 추가한다")
    @Test
    void preservesExistingDataAndAddsConstrainedEmptyRelayOutbox() {
        migrateTo("16");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 8, 12, 30);
        insertWatchOutbox(jdbcTemplate, occurredAt);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_monitor_outbox WHERE event_id = UUID_TO_BIN(?)",
                Long.class,
                WATCH_EVENT_ID
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM relay_event_outbox",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox'",
                String.class
        )).contains(
                "uk_relay_event_outbox_event",
                "idx_relay_event_outbox_pending_claim",
                "idx_relay_event_outbox_expired_claim"
        );
        assertTypedContractColumns(jdbcTemplate);

        insertRelayOutbox(jdbcTemplate, RELAY_EVENT_ID, 1, 1, occurredAt);
        assertThatThrownBy(() -> insertRelayOutbox(
                jdbcTemplate,
                RELAY_EVENT_ID,
                1,
                1,
                occurredAt
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRelayOutbox(
                jdbcTemplate,
                "00000000-0000-0000-0000-000000001714",
                2,
                1,
                occurredAt
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRelayOutbox(
                jdbcTemplate,
                "00000000-0000-0000-0000-000000001715",
                1,
                0,
                occurredAt
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE relay_event_outbox SET publication_status = 'PUBLISHING' "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                RELAY_EVENT_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE relay_event_outbox SET publication_status = 'PUBLISHED' "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                RELAY_EVENT_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE relay_event_outbox SET attempt_count = -1 "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                RELAY_EVENT_ID
        )).isInstanceOf(DataAccessException.class);

        jdbcTemplate.update(
                "UPDATE relay_event_outbox "
                        + "SET publication_status = 'PUBLISHING', "
                        + "attempt_count = 1, "
                        + "lease_token = UUID_TO_BIN(?), "
                        + "lease_expires_at = ? "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                "00000000-0000-0000-0000-000000001716",
                occurredAt.plusMinutes(1),
                RELAY_EVENT_ID
        );
        jdbcTemplate.update(
                "UPDATE relay_event_outbox "
                        + "SET publication_status = 'PUBLISHED', "
                        + "lease_token = NULL, lease_expires_at = NULL, published_at = ? "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                occurredAt.plusSeconds(10),
                RELAY_EVENT_ID
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publication_status FROM relay_event_outbox "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                String.class,
                RELAY_EVENT_ID
        )).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '17'",
                Boolean.class
        )).isTrue();
    }

    private void assertTypedContractColumns(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, numeric_precision FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'contract_version'"
        )).containsEntry("data_type", "int").containsEntry("numeric_precision", 10L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'event_id'"
        )).containsEntry("data_type", "binary").containsEntry("character_maximum_length", 16L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'event_type'"
        )).containsEntry("data_type", "varchar").containsEntry("character_maximum_length", 100L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, numeric_precision FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'event_version'"
        )).containsEntry("data_type", "int").containsEntry("numeric_precision", 10L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'subject_reference'"
        )).containsEntry("data_type", "varchar").containsEntry("character_maximum_length", 128L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, datetime_precision FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'occurred_at'"
        )).containsEntry("data_type", "datetime").containsEntry("datetime_precision", 6L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'relay_event_outbox' "
                        + "AND column_name = 'last_error_code'"
        )).containsEntry("data_type", "varchar").containsEntry("character_maximum_length", 64L);
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

    private void insertWatchOutbox(JdbcTemplate jdbcTemplate, LocalDateTime occurredAt) {
        jdbcTemplate.update(
                "INSERT INTO watch_monitor_outbox ("
                        + "event_id, resource_id, resource_reference, monitoring_state, "
                        + "target_url, occurred_at, available_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', ?, ?, ?)",
                WATCH_EVENT_ID,
                WATCH_RESOURCE_ID,
                "baton-manager:pilot:role-resource:" + WATCH_RESOURCE_ID,
                "https://example.com/existing-watch-resource",
                occurredAt,
                occurredAt
        );
    }

    private void insertRelayOutbox(
            JdbcTemplate jdbcTemplate,
            String eventId,
            int contractVersion,
            int eventVersion,
            LocalDateTime occurredAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO relay_event_outbox ("
                        + "contract_version, event_id, event_type, event_version, "
                        + "subject_reference, occurred_at, available_at"
                        + ") VALUES (?, UUID_TO_BIN(?), ?, ?, ?, ?, ?)",
                contractVersion,
                eventId,
                "ORDER.PAID",
                eventVersion,
                "order:01J4H8Z7A2",
                occurredAt,
                occurredAt
        );
    }
}
