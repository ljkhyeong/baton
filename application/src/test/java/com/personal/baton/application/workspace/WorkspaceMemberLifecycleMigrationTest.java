package com.personal.baton.application.workspace;

import java.time.Instant;
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
class WorkspaceMemberLifecycleMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000501";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000502";
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000503";
    private static final String NEXT_MEMBER_ID = "00000000-0000-0000-0000-000000000504";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000505";
    private static final String DECISION_ID = "00000000-0000-0000-0000-000000000506";
    private static final String HANDOFF_ITEM_ID = "00000000-0000-0000-0000-000000000507";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_member_lifecycle_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V10은 기존 구성원과 역할·결정·바통 참조를 보존하고 활성 상태와 버전을 초기화한다")
    @Test
    void preservesMemberReferencesAndInitializesLifecycleColumns() {
        migrateTo("9");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV9Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT name, deactivated_at, version FROM members WHERE id = UUID_TO_BIN(?)",
                MEMBER_ID
        )).containsEntry("name", "박민서")
                .containsEntry("version", 0L)
                .containsEntry("deactivated_at", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(current_member_id) FROM roles WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROLE_ID
        )).isEqualTo(MEMBER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(next_member_id) FROM roles WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROLE_ID
        )).isEqualTo(NEXT_MEMBER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(author_member_id) FROM decisions WHERE id = UUID_TO_BIN(?)",
                String.class,
                DECISION_ID
        )).isEqualTo(MEMBER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(role_id) FROM handoff_items WHERE id = UUID_TO_BIN(?)",
                String.class,
                HANDOFF_ITEM_ID
        )).isEqualTo(ROLE_ID);
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

    private void seedV9Workspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "구성원 생명주기 이관 팀",
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
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                MEMBER_ID,
                TEAM_ID,
                "박민서"
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                NEXT_MEMBER_ID,
                TEAM_ID,
                "김준호"
        );
        jdbcTemplate.update(
                "INSERT INTO roles ("
                        + "id, team_id, name, purpose, current_member_id, next_member_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?))",
                ROLE_ID,
                TEAM_ID,
                "진행자",
                "모임을 진행합니다",
                MEMBER_ID,
                NEXT_MEMBER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO decisions ("
                        + "id, season_id, title, reason, alternative, created_at, author_member_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, UUID_TO_BIN(?))",
                DECISION_ID,
                SEASON_ID,
                "운영 원칙",
                "운영을 이어 갑니다",
                "",
                Instant.parse("2026-07-20T03:04:05Z"),
                MEMBER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO handoff_items (id, role_id, label, category, completed) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "운영 문서 전달",
                "RESOURCE",
                false
        );
    }
}
