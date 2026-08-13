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
class WorkspaceRecordSearchMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000901";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000902";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000903";
    private static final String HANDOFF_ITEM_ID = "00000000-0000-0000-0000-000000000904";
    private static final String ROLE_RESOURCE_ID = "00000000-0000-0000-0000-000000000905";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_record_search_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V14는 기존 바통과 자료를 보존하고 알 수 없는 생성 시각을 추정하지 않는다")
    @Test
    void preservesLegacyRecordsWithoutInventingCreationTimes() {
        migrateTo("13");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV13Records(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT label FROM handoff_items WHERE id = UUID_TO_BIN(?)",
                String.class,
                HANDOFF_ITEM_ID
        )).isEqualTo("질문 분류 기준을 다음 담당자에게 설명하기");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM role_resources WHERE id = UUID_TO_BIN(?)",
                String.class,
                ROLE_RESOURCE_ID
        )).isEqualTo("질문 정리 가이드");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at IS NULL FROM handoff_items WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                HANDOFF_ITEM_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at IS NULL FROM role_resources WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                ROLE_RESOURCE_ID
        )).isTrue();
        assertThat(columnNullability(jdbcTemplate, "handoff_items")).isEqualTo("YES");
        assertThat(columnNullability(jdbcTemplate, "role_resources")).isEqualTo("YES");
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

    private void seedV13Records(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "기록 탐색 이관 팀",
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
                "질문 큐레이터",
                "스터디 질문을 모아 함께 풉니다"
        );
        jdbcTemplate.update(
                "INSERT INTO handoff_items (id, role_id, label, category, completed) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 분류 기준을 다음 담당자에게 설명하기",
                "ADVICE",
                false
        );
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                ROLE_RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문을 모으고 분류하는 기준"
        );
    }

    private String columnNullability(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND column_name = 'created_at'",
                String.class,
                tableName
        );
    }
}
