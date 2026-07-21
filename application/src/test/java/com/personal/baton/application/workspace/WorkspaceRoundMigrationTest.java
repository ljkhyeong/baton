package com.personal.baton.application.workspace;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceRoundMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000001";
    private static final String FIRST_SEASON_ID = "00000000-0000-0000-0000-000000000011";
    private static final String SECOND_SEASON_ID = "00000000-0000-0000-0000-000000000012";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000021";
    private static final String WAITING_ROUTINE_ID = "00000000-0000-0000-0000-000000000031";
    private static final String DONE_ROUTINE_ID = "00000000-0000-0000-0000-000000000032";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_round_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V5는 기존 루틴 상태와 정의를 날짜 없는 시즌별 이관 회차 실행으로 보존한다")
    @Test
    void migratesV4RoutineStateIntoLegacyRoundExecutions() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
        seedV4Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        List<BackfilledExecution> executions = jdbcTemplate.query(
                "SELECT "
                        + "BIN_TO_UUID(season_round.season_id) AS season_id, "
                        + "season_round.name AS round_name, "
                        + "season_round.meeting_date, "
                        + "BIN_TO_UUID(execution.routine_id) AS routine_id, "
                        + "execution.title, execution.phase, execution.due_label, "
                        + "BIN_TO_UUID(execution.owner_role_id) AS owner_role_id, "
                        + "execution.status, execution.detail, execution.version "
                        + "FROM season_rounds season_round "
                        + "JOIN routine_executions execution "
                        + "ON execution.season_round_id = season_round.id "
                        + "ORDER BY season_id",
                (resultSet, rowNumber) -> {
                    Date meetingDate = resultSet.getDate("meeting_date");
                    return new BackfilledExecution(
                            resultSet.getString("season_id"),
                            resultSet.getString("round_name"),
                            meetingDate == null ? null : meetingDate.toLocalDate(),
                            resultSet.getString("routine_id"),
                            resultSet.getString("title"),
                            resultSet.getString("phase"),
                            resultSet.getString("due_label"),
                            resultSet.getString("owner_role_id"),
                            resultSet.getString("status"),
                            resultSet.getString("detail"),
                            resultSet.getLong("version")
                    );
                }
        );

        assertThat(executions).containsExactly(
                new BackfilledExecution(
                        FIRST_SEASON_ID,
                        "회차 도입 이전 기록",
                        null,
                        WAITING_ROUTINE_ID,
                        "질문 모으기",
                        "BEFORE",
                        "모임 하루 전",
                        ROLE_ID,
                        "WAITING",
                        "질문을 공통 문서에 모읍니다",
                        0L
                ),
                new BackfilledExecution(
                        SECOND_SEASON_ID,
                        "회차 도입 이전 기록",
                        null,
                        DONE_ROUTINE_ID,
                        "결정 정리하기",
                        "AFTER",
                        "모임 직후",
                        ROLE_ID,
                        "DONE",
                        "결정과 남은 질문을 정리합니다",
                        0L
                )
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM season_rounds",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_executions",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM routine_executions execution "
                        + "JOIN routines routine ON routine.id = execution.routine_id "
                        + "JOIN season_rounds season_round ON season_round.id = execution.season_round_id "
                        + "WHERE routine.season_id <> season_round.season_id",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'routines' AND column_name = 'status'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '5'",
                Boolean.class
        )).isTrue();
    }

    private void seedV4Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "이관 검증 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                FIRST_SEASON_ID,
                TEAM_ID,
                "첫 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SECOND_SEASON_ID,
                TEAM_ID,
                "두 번째 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                "진행자",
                "모임을 진행합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routines ("
                        + "id, season_id, title, phase, due_label, owner_role_id, status, detail"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?, ?)",
                WAITING_ROUTINE_ID,
                FIRST_SEASON_ID,
                "질문 모으기",
                "BEFORE",
                "모임 하루 전",
                ROLE_ID,
                "WAITING",
                "질문을 공통 문서에 모읍니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routines ("
                        + "id, season_id, title, phase, due_label, owner_role_id, status, detail"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?, ?)",
                DONE_ROUTINE_ID,
                SECOND_SEASON_ID,
                "결정 정리하기",
                "AFTER",
                "모임 직후",
                ROLE_ID,
                "DONE",
                "결정과 남은 질문을 정리합니다"
        );
    }

    private record BackfilledExecution(
            String seasonId,
            String roundName,
            LocalDate meetingDate,
            String routineId,
            String title,
            String phase,
            String dueLabel,
            String ownerRoleId,
            String status,
            String detail,
            long version
    ) {
    }
}
