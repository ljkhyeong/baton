package com.personal.baton.application.watch;

import java.time.LocalDate;
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
class WatchMonitorOutboxMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000001601";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000001602";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000001603";
    private static final String RESOURCE_ID = "00000000-0000-0000-0000-000000001604";
    private static final String ORPHAN_RESOURCE_ID = "00000000-0000-0000-0000-000000001605";
    private static final String EVENT_ID = "00000000-0000-0000-0000-000000001606";
    private static final String SECOND_EVENT_ID = "00000000-0000-0000-0000-000000001607";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_watch_outbox_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V16은 기존 역할 자료를 보존하고 필요한 제약이 있는 빈 WATCH 아웃박스를 추가한다")
    @Test
    void preservesRoleResourcesAndAddsConstrainedEmptyOutbox() {
        migrateTo("15");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV15RoleResource(jdbcTemplate);

        migrateTo("16");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM role_resources WHERE id = UUID_TO_BIN(?)",
                String.class,
                RESOURCE_ID
        )).isEqualTo("기존 운영 문서");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_monitor_outbox",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() "
                        + "AND table_name = 'watch_monitor_outbox'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'watch_monitor_outbox'",
                String.class
        )).contains(
                "uk_watch_monitor_outbox_event",
                "idx_watch_monitor_outbox_resource_revision",
                "idx_watch_monitor_outbox_pending_claim",
                "idx_watch_monitor_outbox_expired_claim"
        );

        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 1, 11, 30);
        insertOutbox(jdbcTemplate, EVENT_ID, ORPHAN_RESOURCE_ID, "ACTIVE", "https://example.com", occurredAt);
        insertOutbox(jdbcTemplate, SECOND_EVENT_ID, ORPHAN_RESOURCE_ID, "INACTIVE", null, occurredAt);

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM watch_monitor_outbox ORDER BY id",
                Long.class
        )).containsExactly(1L, 2L);
        assertThatThrownBy(() -> insertOutbox(
                jdbcTemplate,
                EVENT_ID,
                ORPHAN_RESOURCE_ID,
                "ACTIVE",
                "https://example.org",
                occurredAt
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertOutbox(
                jdbcTemplate,
                "00000000-0000-0000-0000-000000001608",
                ORPHAN_RESOURCE_ID,
                "INACTIVE",
                "https://example.net",
                occurredAt
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

    private void seedV15RoleResource(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "WATCH 이관 검증 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "2026 파일럿",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "운영 담당",
                "운영 자료를 관리합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description, created_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                RESOURCE_ID,
                ROLE_ID,
                "기존 운영 문서",
                "https://example.com/operations",
                "V15부터 존재하던 자료",
                LocalDateTime.of(2026, 7, 31, 10, 0)
        );
    }

    private void insertOutbox(
            JdbcTemplate jdbcTemplate,
            String eventId,
            String resourceId,
            String monitoringState,
            String targetUrl,
            LocalDateTime occurredAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO watch_monitor_outbox ("
                        + "event_id, resource_id, resource_reference, monitoring_state, "
                        + "target_url, occurred_at, available_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)",
                eventId,
                resourceId,
                "baton-manager:pilot:role-resource:" + resourceId,
                monitoringState,
                targetUrl,
                occurredAt,
                occurredAt
        );
    }
}
