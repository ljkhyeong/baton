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
class WorkspaceMemberCreationMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000401";
    private static final String OTHER_TEAM_ID = "00000000-0000-0000-0000-000000000402";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000403";
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000404";
    private static final String OTHER_MEMBER_ID = "00000000-0000-0000-0000-000000000405";
    private static final String UPPERCASE_MEMBER_ID = "00000000-0000-0000-0000-000000000406";
    private static final String LOWERCASE_MEMBER_ID = "00000000-0000-0000-0000-000000000407";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_member_creation_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V9은 기존 구성원과 멱등 기록을 보존하며 팀별 이름 유일성과 구성원 작업을 추가한다")
    @Test
    void preservesExistingRowsAndAddsMemberCreationConstraints() {
        migrateTo("8");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV8Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM members WHERE id = UUID_TO_BIN(?)",
                String.class,
                MEMBER_ID
        )).isEqualTo("박민서");
        assertThat(jdbcTemplate.queryForList(
                "SELECT operation FROM content_creation_idempotency ORDER BY operation",
                String.class
        )).containsExactly("ROLE");
        assertThat(jdbcTemplate.queryForList(
                "SELECT name FROM members WHERE team_id = UUID_TO_BIN(?) AND name IN ('Alice', 'alice')",
                String.class,
                TEAM_ID
        )).containsExactlyInAnyOrder("Alice", "alice");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT collation_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'members' AND column_name = 'name'",
                String.class
        )).isEqualTo("utf8mb4_bin");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'members' "
                        + "AND constraint_name = 'uk_members_team_name' AND constraint_type = 'UNIQUE'",
                Integer.class
        )).isEqualTo(1);

        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?))",
                TEAM_ID,
                SEASON_ID,
                "MEMBER",
                "c".repeat(64),
                "d".repeat(64),
                MEMBER_ID
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_creation_idempotency WHERE operation = 'MEMBER'",
                Integer.class
        )).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?)",
                TEAM_ID,
                "Alice"
        )).isInstanceOf(DataAccessException.class);
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                OTHER_MEMBER_ID,
                OTHER_TEAM_ID,
                "박민서"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members WHERE name = ?",
                Integer.class,
                "박민서"
        )).isEqualTo(2);

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

    private void seedV8Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "구성원 이관 검증 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                OTHER_TEAM_ID,
                "다른 이관 검증 팀",
                "b".repeat(64)
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
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                MEMBER_ID,
                TEAM_ID,
                "박민서"
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                UPPERCASE_MEMBER_ID,
                TEAM_ID,
                "Alice"
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                LOWERCASE_MEMBER_ID,
                TEAM_ID,
                "alice"
        );
        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(UUID()))",
                TEAM_ID,
                SEASON_ID,
                "ROLE",
                "1".repeat(64),
                "2".repeat(64)
        );
    }
}
