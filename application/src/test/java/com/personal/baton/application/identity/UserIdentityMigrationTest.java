package com.personal.baton.application.identity;

import java.time.Instant;
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
class UserIdentityMigrationTest {

    private static final String FIRST_TEAM_ID = "00000000-0000-0000-0000-000000000901";
    private static final String SECOND_TEAM_ID = "00000000-0000-0000-0000-000000000902";
    private static final String FIRST_MEMBER_ID = "00000000-0000-0000-0000-000000000903";
    private static final String SECOND_MEMBER_ID = "00000000-0000-0000-0000-000000000904";
    private static final String THIRD_MEMBER_ID = "00000000-0000-0000-0000-000000000905";
    private static final String FIRST_ACCOUNT_ID = "00000000-0000-0000-0000-000000000906";
    private static final String SECOND_ACCOUNT_ID = "00000000-0000-0000-0000-000000000907";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_user_identity_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V14는 기존 구성원을 보존하고 팀별 사용자 계정 결속을 하나로 제한한다")
    @Test
    void preservesMembersAndEnforcesOneBindingPerTeamAccount() {
        migrateTo("13");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV13Members(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members",
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_identity_bindings",
                Integer.class
        )).isZero();

        insertAccount(jdbcTemplate, FIRST_ACCOUNT_ID);
        insertAccount(jdbcTemplate, SECOND_ACCOUNT_ID);
        insertBinding(jdbcTemplate, FIRST_MEMBER_ID, FIRST_TEAM_ID, FIRST_ACCOUNT_ID);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT BIN_TO_UUID(team_id) AS team_id, "
                        + "BIN_TO_UUID(user_account_id) AS account_id, bound_at, version "
                        + "FROM member_identity_bindings WHERE member_id = UUID_TO_BIN(?)",
                FIRST_MEMBER_ID
        )).containsEntry("team_id", FIRST_TEAM_ID)
                .containsEntry("account_id", FIRST_ACCOUNT_ID)
                .containsEntry("version", 0L);

        assertThatThrownBy(() -> insertBinding(
                jdbcTemplate,
                SECOND_MEMBER_ID,
                FIRST_TEAM_ID,
                FIRST_ACCOUNT_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertBinding(
                jdbcTemplate,
                FIRST_MEMBER_ID,
                FIRST_TEAM_ID,
                SECOND_ACCOUNT_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertBinding(
                jdbcTemplate,
                SECOND_MEMBER_ID,
                SECOND_TEAM_ID,
                SECOND_ACCOUNT_ID
        )).isInstanceOf(DataAccessException.class);

        insertBinding(jdbcTemplate, THIRD_MEMBER_ID, SECOND_TEAM_ID, FIRST_ACCOUNT_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_identity_bindings",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '14'",
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

    private void seedV13Members(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES "
                        + "(UUID_TO_BIN(?), ?, ?), (UUID_TO_BIN(?), ?, ?)",
                FIRST_TEAM_ID,
                "첫 번째 팀",
                "a".repeat(64),
                SECOND_TEAM_ID,
                "두 번째 팀",
                "b".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?), "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?), "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                FIRST_MEMBER_ID,
                FIRST_TEAM_ID,
                "첫 번째 구성원",
                SECOND_MEMBER_ID,
                FIRST_TEAM_ID,
                "두 번째 구성원",
                THIRD_MEMBER_ID,
                SECOND_TEAM_ID,
                "세 번째 구성원"
        );
    }

    private void insertAccount(JdbcTemplate jdbcTemplate, String accountId) {
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES (UUID_TO_BIN(?), ?)",
                accountId,
                Instant.parse("2026-07-30T12:00:00Z")
        );
    }

    private void insertBinding(
            JdbcTemplate jdbcTemplate,
            String memberId,
            String teamId,
            String accountId
    ) {
        jdbcTemplate.update(
                "INSERT INTO member_identity_bindings ("
                        + "member_id, team_id, user_account_id, bound_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                memberId,
                teamId,
                accountId,
                Instant.parse("2026-07-30T12:01:00Z")
        );
    }
}
