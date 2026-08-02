package com.personal.baton.application.workspace;

import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceSeasonLifecycleMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000601";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000602";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000603";
    private static final String ROUTINE_ID = "00000000-0000-0000-0000-000000000604";
    private static final String NEXT_SEASON_ID = "00000000-0000-0000-0000-000000000605";
    private static final String NEXT_ROLE_ID = "00000000-0000-0000-0000-000000000606";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_season_lifecycle_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V11은 기존 역할을 시즌에 귀속하고 활성 시즌과 복제 계보의 정합성을 강제한다")
    @Test
    void migratesRoleScopeAndEnforcesSeasonLifecycleConstraints() {
        migrateTo("10");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV10Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(season_id) FROM roles WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROLE_ID
        )).isEqualTo(SEASON_ID);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT ended_at, previous_season_id, version FROM seasons "
                        + "WHERE id = UUID_TO_BIN(?)",
                SEASON_ID
        )).containsEntry("ended_at", null)
                .containsEntry("previous_season_id", null)
                .containsEntry("version", 0L);

        assertThatThrownBy(() -> insertSeason(
                jdbcTemplate,
                NEXT_SEASON_ID,
                null,
                "동시 활성 시즌"
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                "UPDATE seasons SET ended_at = '2026-09-01 00:00:00.000000' "
                        + "WHERE id = UUID_TO_BIN(?)",
                SEASON_ID
        );
        insertSeason(jdbcTemplate, NEXT_SEASON_ID, SEASON_ID, "가을 시즌");
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, previous_role_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), ?, ?)",
                NEXT_ROLE_ID,
                TEAM_ID,
                NEXT_SEASON_ID,
                ROLE_ID,
                "진행자",
                "새 시즌을 진행합니다"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO routines ("
                        + "id, season_id, title, phase, due_label, owner_role_id, detail"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?)",
                NEXT_SEASON_ID,
                "잘못된 담당 역할",
                "BEFORE",
                "모임 전",
                ROLE_ID,
                "이전 시즌 역할은 참조할 수 없습니다"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertSeason(
                jdbcTemplate,
                "00000000-0000-0000-0000-000000000607",
                SEASON_ID,
                "중복 후속 시즌"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '11'",
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

    private void seedV10Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "시즌 이관 팀",
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
                "INSERT INTO roles (id, team_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
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
    }

    private void insertSeason(
            JdbcTemplate jdbcTemplate,
            String seasonId,
            String previousSeasonId,
            String name
    ) {
        jdbcTemplate.update(
                "INSERT INTO seasons ("
                        + "id, team_id, name, start_date, end_date, previous_season_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?))",
                seasonId,
                TEAM_ID,
                name,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31),
                previousSeasonId
        );
    }
}
