package com.personal.baton.application.workspace;

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
class WorkspaceSeasonSnapshotMigrationTest {

    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000701";
    private static final String FIRST_SEASON_ID = "00000000-0000-0000-0000-000000000702";
    private static final String SECOND_SEASON_ID = "00000000-0000-0000-0000-000000000703";
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000704";
    private static final String ROLE_ID = "00000000-0000-0000-0000-000000000705";
    private static final String FIRST_DECISION_ID = "00000000-0000-0000-0000-000000000706";
    private static final String SECOND_DECISION_ID = "00000000-0000-0000-0000-000000000707";
    private static final String HANDOFF_ITEM_ID = "00000000-0000-0000-0000-000000000708";
    private static final String RESOURCE_ID = "00000000-0000-0000-0000-000000000709";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_season_snapshot_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V11은 기존 다중 시즌의 역할 참조와 바통·자료·멱등 결과를 시즌 스냅샷으로 이관한다")
    @Test
    void migratesLegacyTeamContentIntoSeasonSnapshots() {
        migrateTo("10");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV10Workspace(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String firstRoleId = findRoleId(jdbcTemplate, FIRST_SEASON_ID);
        String secondRoleId = findRoleId(jdbcTemplate, SECOND_SEASON_ID);

        assertThat(firstRoleId).isEqualTo(ROLE_ID);
        assertThat(secondRoleId).isNotEqualTo(ROLE_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                TEAM_ID
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles "
                        + "WHERE team_id = UUID_TO_BIN(?) "
                        + "AND name = ? AND purpose = ? AND risk = ? AND version = 4",
                Integer.class,
                TEAM_ID,
                "진행자",
                "모임을 안전하게 진행합니다",
                "질문이 늦게 모일 수 있습니다"
        )).isEqualTo(2);
        assertThat(responsibilities(jdbcTemplate, firstRoleId))
                .containsExactly("질문 순서를 정합니다", "발언 시간을 조율합니다");
        assertThat(responsibilities(jdbcTemplate, secondRoleId))
                .containsExactly("질문 순서를 정합니다", "발언 시간을 조율합니다");

        String firstHandoffId = findHandoffId(jdbcTemplate, FIRST_SEASON_ID);
        String secondHandoffId = findHandoffId(jdbcTemplate, SECOND_SEASON_ID);
        assertThat(firstHandoffId).isEqualTo(HANDOFF_ITEM_ID);
        assertThat(secondHandoffId).isNotEqualTo(HANDOFF_ITEM_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handoff_items "
                        + "WHERE label = ? AND category = 'RESOURCE' AND completed = TRUE "
                        + "AND version = 3 AND archived_at = '2026-08-31 12:34:56.123456'",
                Integer.class,
                "질문 문서 권한을 넘깁니다"
        )).isEqualTo(2);

        String firstResourceId = findResourceId(jdbcTemplate, FIRST_SEASON_ID);
        String secondResourceId = findResourceId(jdbcTemplate, SECOND_SEASON_ID);
        assertThat(firstResourceId).isEqualTo(RESOURCE_ID);
        assertThat(secondResourceId).isNotEqualTo(RESOURCE_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_resources "
                        + "WHERE title = ? AND url = ? AND description = ? AND version = 2",
                Integer.class,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문 분류 기준"
        )).isEqualTo(2);

        assertThat(findDecisionRoleId(jdbcTemplate, FIRST_DECISION_ID)).isEqualTo(firstRoleId);
        assertThat(findDecisionRoleId(jdbcTemplate, SECOND_DECISION_ID)).isEqualTo(secondRoleId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM decision_roles decision_role "
                        + "JOIN decisions decision_record ON decision_record.id = decision_role.decision_id "
                        + "JOIN roles role_record ON role_record.id = decision_role.role_id "
                        + "WHERE decision_record.season_id <> role_record.season_id",
                Integer.class
        )).isZero();

        assertThat(findIdempotencyResourceId(jdbcTemplate, "ROLE")).isEqualTo(secondRoleId);
        assertThat(findIdempotencyResourceId(jdbcTemplate, "HANDOFF_ITEM")).isEqualTo(secondHandoffId);
        assertThat(findIdempotencyResourceId(jdbcTemplate, "ROLE_RESOURCE")).isEqualTo(secondResourceId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ended_at IS NOT NULL FROM seasons WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                FIRST_SEASON_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ended_at IS NULL FROM seasons WHERE id = UUID_TO_BIN(?)",
                Boolean.class,
                SECOND_SEASON_ID
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
                "INSERT INTO teams ("
                        + "id, name, access_key_hash, idempotency_key_hash, "
                        + "creation_request_fingerprint, creation_season_id"
                        + ") VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, UUID_TO_BIN(?))",
                TEAM_ID,
                "시즌 snapshot 이관 팀",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                FIRST_SEASON_ID
        );
        insertSeason(
                jdbcTemplate,
                FIRST_SEASON_ID,
                "첫 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        insertSeason(
                jdbcTemplate,
                SECOND_SEASON_ID,
                "두 번째 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                MEMBER_ID,
                TEAM_ID,
                "박민서"
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, name, purpose, risk, version) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                ROLE_ID,
                TEAM_ID,
                "진행자",
                "모임을 안전하게 진행합니다",
                "질문이 늦게 모일 수 있습니다",
                4L
        );
        jdbcTemplate.update(
                "INSERT INTO role_responsibilities (role_id, sort_order, responsibility) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                0,
                "질문 순서를 정합니다"
        );
        jdbcTemplate.update(
                "INSERT INTO role_responsibilities (role_id, sort_order, responsibility) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                1,
                "발언 시간을 조율합니다"
        );
        insertDecision(jdbcTemplate, FIRST_DECISION_ID, FIRST_SEASON_ID, "첫 시즌 질문 마감");
        insertDecision(jdbcTemplate, SECOND_DECISION_ID, SECOND_SEASON_ID, "두 번째 시즌 질문 마감");
        jdbcTemplate.update(
                "INSERT INTO handoff_items ("
                        + "id, role_id, label, category, completed, archived_at, version"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)",
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 문서 권한을 넘깁니다",
                "RESOURCE",
                true,
                "2026-08-31 12:34:56.123456",
                3L
        );
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description, version) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문 분류 기준",
                2L
        );
        insertIdempotency(
                jdbcTemplate,
                "ROLE",
                "1".repeat(64),
                "2".repeat(64),
                ROLE_ID
        );
        insertIdempotency(
                jdbcTemplate,
                "HANDOFF_ITEM",
                "3".repeat(64),
                "4".repeat(64),
                HANDOFF_ITEM_ID
        );
        insertIdempotency(
                jdbcTemplate,
                "ROLE_RESOURCE",
                "5".repeat(64),
                "6".repeat(64),
                RESOURCE_ID
        );
    }

    private void insertSeason(
            JdbcTemplate jdbcTemplate,
            String seasonId,
            String name,
            LocalDate startDate,
            LocalDate endDate
    ) {
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                seasonId,
                TEAM_ID,
                name,
                startDate,
                endDate
        );
    }

    private void insertDecision(
            JdbcTemplate jdbcTemplate,
            String decisionId,
            String seasonId,
            String title
    ) {
        jdbcTemplate.update(
                "INSERT INTO decisions ("
                        + "id, season_id, title, reason, alternative, created_at, author_member_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, UUID_TO_BIN(?))",
                decisionId,
                seasonId,
                title,
                "질문 정리 시간을 확보합니다",
                "모임 당일까지 받는 방안을 검토했습니다",
                "2026-07-20 03:04:05.000000",
                MEMBER_ID
        );
        jdbcTemplate.update(
                "INSERT INTO decision_roles (decision_id, sort_order, role_id) "
                        + "VALUES (UUID_TO_BIN(?), 0, UUID_TO_BIN(?))",
                decisionId,
                ROLE_ID
        );
    }

    private void insertIdempotency(
            JdbcTemplate jdbcTemplate,
            String operation,
            String idempotencyHash,
            String requestFingerprint,
            String resourceId
    ) {
        jdbcTemplate.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?))",
                TEAM_ID,
                SECOND_SEASON_ID,
                operation,
                idempotencyHash,
                requestFingerprint,
                resourceId
        );
    }

    private String findRoleId(JdbcTemplate jdbcTemplate, String seasonId) {
        return jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM roles WHERE season_id = UUID_TO_BIN(?)",
                String.class,
                seasonId
        );
    }

    private List<String> responsibilities(JdbcTemplate jdbcTemplate, String roleId) {
        return jdbcTemplate.queryForList(
                "SELECT responsibility FROM role_responsibilities "
                        + "WHERE role_id = UUID_TO_BIN(?) ORDER BY sort_order",
                String.class,
                roleId
        );
    }

    private String findHandoffId(JdbcTemplate jdbcTemplate, String seasonId) {
        return jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(handoff_item.id) "
                        + "FROM handoff_items handoff_item "
                        + "JOIN roles role_record ON role_record.id = handoff_item.role_id "
                        + "WHERE role_record.season_id = UUID_TO_BIN(?)",
                String.class,
                seasonId
        );
    }

    private String findResourceId(JdbcTemplate jdbcTemplate, String seasonId) {
        return jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(role_resource.id) "
                        + "FROM role_resources role_resource "
                        + "JOIN roles role_record ON role_record.id = role_resource.role_id "
                        + "WHERE role_record.season_id = UUID_TO_BIN(?)",
                String.class,
                seasonId
        );
    }

    private String findDecisionRoleId(JdbcTemplate jdbcTemplate, String decisionId) {
        return jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(role_id) FROM decision_roles WHERE decision_id = UUID_TO_BIN(?)",
                String.class,
                decisionId
        );
    }

    private String findIdempotencyResourceId(JdbcTemplate jdbcTemplate, String operation) {
        return jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(resource_id) FROM content_creation_idempotency "
                        + "WHERE season_id = UUID_TO_BIN(?) AND operation = ?",
                String.class,
                SECOND_SEASON_ID,
                operation
        );
    }
}
