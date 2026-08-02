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
class WorkspaceRoundAutomationMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000701";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000702";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000703";
    private static final String ROUTINE_ID = "00000000-0000-0000-0000-000000000704";
    private static final String ROUND_ID = "00000000-0000-0000-0000-000000000705";
    private static final String EXECUTION_ID = "00000000-0000-0000-0000-000000000706";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_round_automation_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V12는 기존 기록을 수동 회차와 서울 시간대로 보존하고 자동 회차 및 마감 제약을 추가한다")
    @Test
    void migratesExistingRecordsAndEnforcesAutomationConstraints() {
        migrateTo("11");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV11Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT time_zone, round_schedule_first_meeting_date "
                        + "FROM seasons WHERE id = UUID_TO_BIN(?)",
                SEASON_ID
        )).containsEntry("time_zone", "Asia/Seoul")
                .containsEntry("round_schedule_first_meeting_date", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT origin, scheduled_occurrence_date, scheduled_at "
                        + "FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                ROUND_ID
        )).containsEntry("origin", "MANUAL")
                .containsEntry("scheduled_occurrence_date", null)
                .containsEntry("scheduled_at", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT deadline_day_offset, deadline_time FROM routines "
                        + "WHERE id = UUID_TO_BIN(?)",
                ROUTINE_ID
        )).containsEntry("deadline_day_offset", null)
                .containsEntry("deadline_time", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT deadline_day_offset, deadline_time, deadline_at "
                        + "FROM routine_executions WHERE id = UUID_TO_BIN(?)",
                EXECUTION_ID
        )).containsEntry("deadline_day_offset", null)
                .containsEntry("deadline_time", null)
                .containsEntry("deadline_at", null);

        configureValidSchedule(jdbcTemplate);
        insertAutomaticRound(jdbcTemplate, "00000000-0000-0000-0000-000000000707", "자동 1회차");

        assertThatThrownBy(() -> insertAutomaticRound(
                jdbcTemplate,
                "00000000-0000-0000-0000-000000000708",
                "중복 자동 회차"
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE routines SET deadline_day_offset = 1 WHERE id = UUID_TO_BIN(?)",
                ROUTINE_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE routines SET deadline_time = '10:00:00' WHERE id = UUID_TO_BIN(?)",
                ROUTINE_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE routine_executions "
                        + "SET deadline_day_offset = 1, deadline_time = '10:00:00' "
                        + "WHERE id = UUID_TO_BIN(?)",
                EXECUTION_ID
        )).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '12'",
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

    private void seedV11Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "회차 자동화 이관 팀",
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
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "진행자",
                "모임을 진행합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routines ("
                        + "id, season_id, title, phase, due_label, owner_role_id, detail"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?)",
                ROUTINE_ID,
                SEASON_ID,
                "질문 모으기",
                "BEFORE",
                "모임 전",
                ROLE_ID,
                "질문을 한곳에 모읍니다"
        );
        jdbcTemplate.update(
                "INSERT INTO season_rounds (id, season_id, name, meeting_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROUND_ID,
                SEASON_ID,
                "기존 1회차",
                LocalDate.of(2026, 7, 28)
        );
        jdbcTemplate.update(
                "INSERT INTO routine_executions ("
                        + "id, season_round_id, routine_id, title, phase, due_label, "
                        + "owner_role_id, status, detail"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "?, ?, ?, UUID_TO_BIN(?), ?, ?)",
                EXECUTION_ID,
                ROUND_ID,
                ROUTINE_ID,
                "질문 모으기",
                "BEFORE",
                "모임 전",
                ROLE_ID,
                "WAITING",
                "질문을 한곳에 모읍니다"
        );
    }

    private void configureValidSchedule(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "UPDATE seasons SET "
                        + "round_schedule_first_meeting_date = ?, "
                        + "round_schedule_meeting_time = '19:30:00', "
                        + "round_schedule_recurrence = 'WEEKLY', "
                        + "round_schedule_generation_lead_days = 7, "
                        + "round_schedule_enabled = TRUE, "
                        + "round_schedule_next_occurrence_date = ? "
                        + "WHERE id = UUID_TO_BIN(?)",
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                SEASON_ID
        );
    }

    private void insertAutomaticRound(JdbcTemplate jdbcTemplate, String roundId, String name) {
        jdbcTemplate.update(
                "INSERT INTO season_rounds ("
                        + "id, season_id, name, meeting_date, origin, "
                        + "scheduled_occurrence_date, scheduled_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, 'AUTOMATIC', ?, ?)",
                roundId,
                SEASON_ID,
                name,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                "2026-08-10 10:30:00.000000"
        );
    }
}
