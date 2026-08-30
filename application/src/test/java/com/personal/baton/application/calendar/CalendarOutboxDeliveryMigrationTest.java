package com.personal.baton.application.calendar;

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
class CalendarOutboxDeliveryMigrationTest {

    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 8, 25, 12, 0, 0, 123_456_000);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_calendar_delivery_migration")
            .withUsername("baton")
            .withPassword("password");

    @Test
    @DisplayName("V23과 V29는 기존 CAL 대기 행을 보존하고 시즌 이름 테이블을 별도로 추가한다")
    void preservesPendingRowsAndAddsLeaseConstraint() {
        migrateTo("22");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
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
                    at_instant,
                    source_updated_at,
                    available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                    'ACTIVE', '기존 일정', 'UTC_POINT', ?, ?, ?
                )
                """,
                "10000000-0000-0000-0000-000000000001",
                "20000000-0000-0000-0000-000000000001",
                "30000000-0000-0000-0000-000000000001",
                CREATED_AT,
                CREATED_AT.plusDays(1),
                CREATED_AT,
                CREATED_AT
        );

        migrateTo("23");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM calendar_snapshot_outbox",
                String.class
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lease_token FROM calendar_snapshot_outbox",
                byte[].class
        )).isNull();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE calendar_snapshot_outbox SET delivery_status = 'PROCESSING'"
        )).isInstanceOf(DataAccessException.class);

        var previous = jdbcTemplate.queryForMap("SELECT * FROM calendar_snapshot_outbox");
        migrateTo("29");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM calendar_snapshot_outbox"))
                .usingRecursiveComparison().isEqualTo(previous);
        jdbcTemplate.update(
                """
                INSERT INTO calendar_season_metadata_outbox (season_id, display_name, occurred_at, available_at)
                VALUES (UUID_TO_BIN(?), '여름 시즌', ?, ?)
                """, "30000000-0000-0000-0000-000000000001", CREATED_AT, CREATED_AT
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM calendar_season_metadata_outbox", String.class
        )).isEqualTo("PENDING");
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
