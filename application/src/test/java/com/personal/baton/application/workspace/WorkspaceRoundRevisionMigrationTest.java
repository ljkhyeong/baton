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
class WorkspaceRoundRevisionMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000301";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000302";
    private static final String ROUND_ID = "00000000-0000-0000-0000-000000000303";
    private static final String LEGACY_ROUND_ID = "00000000-0000-0000-0000-000000000304";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_round_revision_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V8은 기존 회차를 활성 상태와 버전 0으로 보존한다")
    @Test
    void preservesExistingRoundAndAddsRevisionColumns() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("7")
                .load()
                .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
        seedV7Round(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROUND_ID
        )).isEqualTo("기존 회차");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meeting_date FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                LocalDate.class,
                ROUND_ID
        )).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                Long.class,
                ROUND_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                ROUND_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meeting_date FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                LocalDate.class,
                LEGACY_ROUND_ID
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                Long.class,
                LEGACY_ROUND_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM season_rounds WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                LEGACY_ROUND_ID
        )).isTrue();
    }

    private void seedV7Round(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "회차 이관 검증 팀",
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
                "INSERT INTO season_rounds (id, season_id, name, meeting_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROUND_ID,
                SEASON_ID,
                "기존 회차",
                LocalDate.of(2026, 7, 28)
        );
        jdbcTemplate.update(
                "INSERT INTO season_rounds (id, season_id, name, meeting_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, NULL)",
                LEGACY_ROUND_ID,
                SEASON_ID,
                "회차 도입 이전 기록"
        );
    }
}
