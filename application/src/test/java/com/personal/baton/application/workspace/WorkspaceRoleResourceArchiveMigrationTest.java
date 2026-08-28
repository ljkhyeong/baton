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
class WorkspaceRoleResourceArchiveMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000002401";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000002402";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000002403";
    private static final String RESOURCE_ID = "00000000-0000-0000-0000-000000002404";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_role_resource_archive_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V24는 기존 역할 자료를 활성 상태로 보존하고 보관 시각을 추가한다")
    @Test
    void preservesExistingRoleResourcesAsActive() {
        migrateTo("23");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV23RoleResource(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM role_resources WHERE id = UUID_TO_BIN(?)",
                String.class,
                RESOURCE_ID
        )).isEqualTo("기존 운영 자료");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM role_resources WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                RESOURCE_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM role_resources WHERE id = UUID_TO_BIN(?)",
                Long.class,
                RESOURCE_ID
        )).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'role_resources' "
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

    private void seedV23RoleResource(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "역할 자료 보관 이관 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "2026 가을",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 11, 30)
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "진행자",
                "모임 진행 자료를 관리합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description, created_at, version) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)",
                RESOURCE_ID,
                ROLE_ID,
                "기존 운영 자료",
                "https://docs.example.com/operations",
                "기존 역할 자료를 보존합니다",
                "2026-08-20 03:04:05.123456",
                4L
        );
    }
}
