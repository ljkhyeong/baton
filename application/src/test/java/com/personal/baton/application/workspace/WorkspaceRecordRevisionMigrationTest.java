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
class WorkspaceRecordRevisionMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000201";
    private static final String SEASON_ID = "00000000-0000-0000-0000-000000000202";
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000203";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000204";
    private static final String DECISION_ID = "00000000-0000-0000-0000-000000000205";
    private static final String HANDOFF_ITEM_ID = "00000000-0000-0000-0000-000000000206";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_record_revision_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V7은 기존 결정과 바통을 활성 상태와 버전 0으로 보존한다")
    @Test
    void preservesExistingRecordsAndAddsRevisionColumns() {
        migrateTo("6");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV6Records(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM decisions WHERE id = UUID_TO_BIN(?)",
                Long.class,
                DECISION_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM decisions WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                DECISION_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM handoff_items WHERE id = UUID_TO_BIN(?)",
                Long.class,
                HANDOFF_ITEM_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at IS NULL FROM handoff_items WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                HANDOFF_ITEM_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT BIN_TO_UUID(role_id) FROM decision_roles "
                        + "WHERE decision_id = UUID_TO_BIN(?) ORDER BY sort_order",
                String.class,
                DECISION_ID
        )).containsExactly(ROLE_ID);
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

    private void seedV6Records(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "기록 이관 검증 팀",
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
                "INSERT INTO roles (id, team_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                "기록자",
                "팀의 맥락을 남깁니다"
        );
        jdbcTemplate.update(
                "INSERT INTO decisions ("
                        + "id, season_id, title, reason, alternative, created_at, author_member_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, UUID_TO_BIN(?))",
                DECISION_ID,
                SEASON_ID,
                "질문을 전날 마감한다",
                "준비 시간을 확보합니다",
                "당일에도 받는 방안을 검토했습니다",
                "2026-07-20 03:04:05.000000",
                MEMBER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO decision_roles (decision_id, sort_order, role_id) "
                        + "VALUES (UUID_TO_BIN(?), 0, UUID_TO_BIN(?))",
                DECISION_ID,
                ROLE_ID
        );
        jdbcTemplate.update(
                "INSERT INTO handoff_items (id, role_id, label, category, completed) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 문서 권한 넘기기",
                "RESOURCE",
                true
        );
    }
}
