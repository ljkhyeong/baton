package com.personal.baton.application.workspace;

import java.time.LocalDate;
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
class WorkspaceRoutineArchiveMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000a01";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000a02";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000a03";
    private static final String PREVIOUS_ROUTINE_ID = "00000000-0000-0000-0000-000000000a04";
    private static final String ROUTINE_ID = "00000000-0000-0000-0000-000000000a05";
    private static final String ROUND_ID = "00000000-0000-0000-0000-000000000a06";
    private static final String EXECUTION_ID = "00000000-0000-0000-0000-000000000a07";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_routine_archive_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V15는 기존 루틴 계보와 실행 연결 및 버전을 보존하고 nullable 보관 시각을 추가한다")
    @Test
    void preservesExistingRoutinesAsActive() {
        migrateTo("14");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV14Routine(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM routines WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROUTINE_ID
        )).isEqualTo("기존 회고 준비");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM routines WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                ROUTINE_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM routines WHERE id = UUID_TO_BIN(?)",
                Long.class,
                ROUTINE_ID
        )).isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routines current_routine "
                        + "JOIN routines previous_routine "
                        + "ON previous_routine.id = current_routine.previous_routine_id "
                        + "WHERE current_routine.id = UUID_TO_BIN(?) "
                        + "AND previous_routine.id = UUID_TO_BIN(?)",
                Long.class,
                ROUTINE_ID,
                PREVIOUS_ROUTINE_ID
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM routine_executions WHERE id = UUID_TO_BIN(?)",
                Long.class,
                EXECUTION_ID
        )).isEqualTo(11L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_executions execution_record "
                        + "JOIN routines routine ON routine.id = execution_record.routine_id "
                        + "JOIN season_rounds season_round "
                        + "ON season_round.id = execution_record.season_round_id "
                        + "WHERE execution_record.id = UUID_TO_BIN(?) "
                        + "AND routine.id = UUID_TO_BIN(?) "
                        + "AND season_round.id = UUID_TO_BIN(?)",
                Long.class,
                EXECUTION_ID,
                ROUTINE_ID,
                ROUND_ID
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'routines' "
                        + "AND column_name = 'archived_at'",
                String.class
        )).isEqualTo("YES");
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

    private void seedV14Routine(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "루틴 보관 이관 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "회고 진행자",
                "회고를 진행합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO routines (id, season_id, title, phase, due_label, owner_role_id, "
                        + "detail, version) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, "
                        + "UUID_TO_BIN(?), ?, ?)",
                PREVIOUS_ROUTINE_ID,
                SEASON_ID,
                "이전 회고 준비",
                "BEFORE",
                "모임 전날",
                ROLE_ID,
                "회고 질문을 준비합니다",
                3L
        );
        jdbcTemplate.update(
                "INSERT INTO routines (id, season_id, previous_routine_id, title, phase, due_label, "
                        + "owner_role_id, detail, version) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?, ?)",
                ROUTINE_ID,
                SEASON_ID,
                PREVIOUS_ROUTINE_ID,
                "기존 회고 준비",
                "BEFORE",
                "모임 전날",
                ROLE_ID,
                "회고 질문을 준비합니다",
                7L
        );
        jdbcTemplate.update(
                "INSERT INTO season_rounds (id, season_id, name, meeting_date, version) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                ROUND_ID,
                SEASON_ID,
                "기존 1회차",
                LocalDate.of(2026, 7, 28),
                5L
        );
        jdbcTemplate.update(
                "INSERT INTO routine_executions ("
                        + "id, season_round_id, routine_id, title, phase, due_label, "
                        + "owner_role_id, status, detail, version"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "?, ?, ?, UUID_TO_BIN(?), ?, ?, ?)",
                EXECUTION_ID,
                ROUND_ID,
                ROUTINE_ID,
                "기존 회고 준비",
                "BEFORE",
                "모임 전날",
                ROLE_ID,
                "DONE",
                "회고 질문을 준비합니다",
                11L
        );
    }
}
