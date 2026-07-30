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
class WorkspaceRoleHandoffMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000801";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000802";
    private static final String FROM_MEMBER_ID = "00000000-0000-0000-0000-000000000803";
    private static final String TO_MEMBER_ID = "00000000-0000-0000-0000-000000000804";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000805";
    private static final String FIRST_HANDOFF_ID = "00000000-0000-0000-0000-000000000806";
    private static final String SECOND_HANDOFF_ID = "00000000-0000-0000-0000-000000000807";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_role_handoff_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V13은 기존 역할을 보존하고 역할마다 열린 바통을 하나만 허용한다")
    @Test
    void preservesRolesAndEnforcesOneOpenHandoffPerRole() {
        migrateTo("12");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV12Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE id = UUID_TO_BIN(?)",
                Integer.class,
                ROLE_ID
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_handoffs",
                Integer.class
        )).isZero();

        insertPreparingHandoff(jdbcTemplate, FIRST_HANDOFF_ID);
        assertThatThrownBy(() -> insertPreparingHandoff(jdbcTemplate, SECOND_HANDOFF_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE role_handoffs "
                        + "SET status = 'CANCELLED', cancelled_at = ?, "
                        + "cancelled_by_member_id = UUID_TO_BIN(?) "
                        + "WHERE id = UUID_TO_BIN(?)",
                "2026-07-30 09:05:00.000000",
                TO_MEMBER_ID,
                FIRST_HANDOFF_ID
        )).isInstanceOf(DataAccessException.class);

        jdbcTemplate.update(
                "UPDATE role_handoffs "
                        + "SET status = 'CANCELLED', cancelled_at = ?, "
                        + "cancelled_by_member_id = UUID_TO_BIN(?) "
                        + "WHERE id = UUID_TO_BIN(?)",
                "2026-07-30 09:10:00.000000",
                FROM_MEMBER_ID,
                FIRST_HANDOFF_ID
        );
        insertPreparingHandoff(jdbcTemplate, SECOND_HANDOFF_ID);

        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, "
                        + "request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "'ROLE_HANDOFF', ?, ?, UUID_TO_BIN(?))",
                TEAM_ID,
                SEASON_ID,
                "b".repeat(64),
                "c".repeat(64),
                SECOND_HANDOFF_ID
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '13'",
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

    private void seedV12Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "바통 이관 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?), "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                FROM_MEMBER_ID,
                TEAM_ID,
                "이전 담당자",
                TO_MEMBER_ID,
                TEAM_ID,
                "다음 담당자"
        );
        jdbcTemplate.update(
                "INSERT INTO roles ("
                        + "id, team_id, season_id, name, purpose, current_member_id, "
                        + "next_member_id, assignment_start_date, assignment_end_date"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, "
                        + "UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "진행자",
                "모임을 진행합니다",
                FROM_MEMBER_ID,
                TO_MEMBER_ID,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31)
        );
    }

    private void insertPreparingHandoff(JdbcTemplate jdbcTemplate, String handoffId) {
        jdbcTemplate.update(
                "INSERT INTO role_handoffs ("
                        + "id, team_id, season_id, role_id, from_member_id, to_member_id, "
                        + "outgoing_assignment_start_date, outgoing_assignment_end_date, "
                        + "incoming_assignment_start_date, incoming_assignment_end_date, "
                        + "status, prepared_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)",
                handoffId,
                TEAM_ID,
                SEASON_ID,
                ROLE_ID,
                FROM_MEMBER_ID,
                TO_MEMBER_ID,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "PREPARING",
                "2026-07-30 09:00:00.000000"
        );
    }
}
