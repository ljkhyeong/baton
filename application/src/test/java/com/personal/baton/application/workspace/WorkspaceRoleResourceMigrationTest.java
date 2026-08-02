package com.personal.baton.application.workspace;

import java.time.LocalDate;
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
class WorkspaceRoleResourceMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000101";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000102";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000103";
    private static final String RESOURCE_ID = "00000000-0000-0000-0000-000000000104";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_resource_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V6는 기존 콘텐츠 멱등 기록을 보존하며 역할 자료와 새 작업 종류를 추가한다")
    @Test
    void preservesExistingIdempotencyRowsAndAddsRoleResources() {
        migrateTo("5");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV5Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForList(
                "SELECT operation FROM content_creation_idempotency ORDER BY operation",
                String.class
        )).containsExactly("HANDOFF_ITEM", "ROLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'role_resources' "
                        + "AND column_name IN ('id', 'role_id', 'title', 'url', 'description', 'version')",
                Integer.class
        )).isEqualTo(6);

        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문 분류 기준"
        );
        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?))",
                TEAM_ID,
                SEASON_ID,
                "ROLE_RESOURCE",
                "c".repeat(64),
                "d".repeat(64),
                RESOURCE_ID
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM role_resources WHERE id = UUID_TO_BIN(?)",
                Long.class,
                RESOURCE_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_creation_idempotency WHERE operation = 'ROLE_RESOURCE'",
                Integer.class
        )).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(UUID()))",
                TEAM_ID,
                SEASON_ID,
                "UNSUPPORTED",
                "e".repeat(64),
                "f".repeat(64)
        )).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '6'",
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

    private void seedV5Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "자료 이관 검증 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "파일럿 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                "진행자",
                "모임을 진행합니다"
        );
        insertIdempotency(jdbcTemplate, "ROLE", "1".repeat(64), "2".repeat(64));
        insertIdempotency(jdbcTemplate, "HANDOFF_ITEM", "3".repeat(64), "4".repeat(64));
    }

    private void insertIdempotency(
            JdbcTemplate jdbcTemplate,
            String operation,
            String idempotencyHash,
            String requestFingerprint
    ) {
        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(UUID()))",
                TEAM_ID,
                SEASON_ID,
                operation,
                idempotencyHash,
                requestFingerprint
        );
    }
}
