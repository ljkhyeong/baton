package com.personal.baton.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class IdentityBootstrapMigrationTest {

    private static final String TEAM_ID = "00000000-0000-4000-8000-000000001501";
    private static final String FIRST_MEMBER_ID = "00000000-0000-4000-8000-000000001502";
    private static final String SECOND_MEMBER_ID = "00000000-0000-4000-8000-000000001503";
    private static final String FIRST_ACCOUNT_ID = "00000000-0000-4000-8000-000000001504";
    private static final String SECOND_ACCOUNT_ID = "00000000-0000-4000-8000-000000001505";
    private static final String INVITATION_ID = "00000000-0000-4000-8000-000000001506";
    private static final String MEMBER_INVITATION_ID =
            "00000000-0000-4000-8000-000000001509";
    private static final String EXTERNAL_IDENTITY_ID =
            "00000000-0000-4000-8000-000000001507";
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_identity_bootstrap_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V16과 V17은 기존 결속을 보존하고 OWNER·구성원 초대 및 JDBC 세션 제약을 추가한다")
    @Test
    void migratesV15IdentityDataAndAddsBootstrapAndSessionConstraints() {
        migrateTo("15");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV15Identity(jdbcTemplate);

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<String, Object> preserved = jdbcTemplate.queryForMap(
                "SELECT role, owner_team_id, BIN_TO_UUID(user_account_id) AS account_id "
                        + "FROM member_identity_bindings WHERE member_id = UUID_TO_BIN(?)",
                FIRST_MEMBER_ID
        );
        assertThat(preserved)
                .containsEntry("role", "MEMBER")
                .containsEntry("account_id", FIRST_ACCOUNT_ID)
                .containsEntry("owner_team_id", null);

        jdbcTemplate.update(
                "UPDATE member_identity_bindings SET role = 'OWNER' "
                        + "WHERE member_id = UUID_TO_BIN(?)",
                FIRST_MEMBER_ID
        );
        assertThatThrownBy(() -> insertBinding(
                jdbcTemplate,
                SECOND_MEMBER_ID,
                SECOND_ACCOUNT_ID,
                "OWNER"
        )).isInstanceOf(DataAccessException.class);

        insertExternalIdentity(jdbcTemplate);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO oidc_external_identities ("
                        + "id, identity_key_hash, issuer, subject, user_account_id, linked_at"
                        + ") VALUES (UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?)",
                "00000000-0000-4000-8000-000000001508",
                "c".repeat(64),
                "https://login.example.com",
                "different-subject",
                SECOND_ACCOUNT_ID,
                NOW
        )).isInstanceOf(DataAccessException.class);

        insertInvitation(jdbcTemplate);
        Map<String, Object> storedInvitation = jdbcTemplate.queryForMap(
                "SELECT idempotency_key_hash, token_hash, consumed_at, consumed_by_account_id "
                        + "FROM owner_bootstrap_invitations WHERE id = UUID_TO_BIN(?)",
                INVITATION_ID
        );
        assertThat(storedInvitation)
                .containsEntry("idempotency_key_hash", "d".repeat(64))
                .containsEntry("token_hash", "e".repeat(64))
                .containsEntry("consumed_at", null)
                .containsEntry("consumed_by_account_id", null);
        assertThat(jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'owner_bootstrap_invitations'",
                String.class
        )).doesNotContain("token", "idempotency_key");

        insertMemberInvitation(jdbcTemplate);
        Map<String, Object> storedMemberInvitation = jdbcTemplate.queryForMap(
                "SELECT idempotency_key_hash, token_hash, "
                        + "revoked_at, revoked_by_account_id, "
                        + "consumed_at, consumed_by_account_id "
                        + "FROM member_invitations WHERE id = UUID_TO_BIN(?)",
                MEMBER_INVITATION_ID
        );
        assertThat(storedMemberInvitation)
                .containsEntry("idempotency_key_hash", "f".repeat(64))
                .containsEntry("token_hash", "1".repeat(64))
                .containsEntry("revoked_at", null)
                .containsEntry("revoked_by_account_id", null)
                .containsEntry("consumed_at", null)
                .containsEntry("consumed_by_account_id", null);
        assertThat(jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'member_invitations'",
                String.class
        )).doesNotContain("token", "idempotency_key");
        assertThatThrownBy(() -> insertInvalidTerminalMemberInvitation(jdbcTemplate))
                .isInstanceOf(DataAccessException.class);

        verifyOfficialSpringSessionTables(jdbcTemplate);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '16'",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '17'",
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

    private void seedV15Identity(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "V15 보존 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?), "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                FIRST_MEMBER_ID,
                TEAM_ID,
                "첫 번째 구성원",
                SECOND_MEMBER_ID,
                TEAM_ID,
                "두 번째 구성원"
        );
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES "
                        + "(UUID_TO_BIN(?), ?), (UUID_TO_BIN(?), ?)",
                FIRST_ACCOUNT_ID,
                NOW.minusSeconds(60),
                SECOND_ACCOUNT_ID,
                NOW.minusSeconds(60)
        );
        jdbcTemplate.update(
                "INSERT INTO member_identity_bindings ("
                        + "member_id, team_id, user_account_id, bound_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                FIRST_MEMBER_ID,
                TEAM_ID,
                FIRST_ACCOUNT_ID,
                NOW.minusSeconds(30)
        );
    }

    private void insertBinding(
            JdbcTemplate jdbcTemplate,
            String memberId,
            String accountId,
            String role
    ) {
        jdbcTemplate.update(
                "INSERT INTO member_identity_bindings ("
                        + "member_id, team_id, user_account_id, bound_at, role"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                memberId,
                TEAM_ID,
                accountId,
                NOW,
                role
        );
    }

    private void insertExternalIdentity(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO oidc_external_identities ("
                        + "id, identity_key_hash, issuer, subject, user_account_id, linked_at"
                        + ") VALUES (UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?)",
                EXTERNAL_IDENTITY_ID,
                "c".repeat(64),
                "https://login.example.com",
                "subject-1501",
                FIRST_ACCOUNT_ID,
                NOW
        );
    }

    private void insertInvitation(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO owner_bootstrap_invitations ("
                        + "id, team_id, member_id, idempotency_key_hash, token_hash, "
                        + "issued_at, expires_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                INVITATION_ID,
                TEAM_ID,
                SECOND_MEMBER_ID,
                "d".repeat(64),
                "e".repeat(64),
                NOW,
                NOW.plusSeconds(3600)
        );
    }

    private void insertMemberInvitation(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO member_invitations ("
                        + "id, team_id, member_id, issued_by_account_id, "
                        + "idempotency_key_hash, token_hash, issued_at, expires_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), ?, ?, ?, ?)",
                MEMBER_INVITATION_ID,
                TEAM_ID,
                SECOND_MEMBER_ID,
                FIRST_ACCOUNT_ID,
                "f".repeat(64),
                "1".repeat(64),
                NOW,
                NOW.plusSeconds(24 * 60 * 60)
        );
    }

    private void insertInvalidTerminalMemberInvitation(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO member_invitations ("
                        + "id, team_id, member_id, issued_by_account_id, "
                        + "idempotency_key_hash, token_hash, issued_at, expires_at, "
                        + "revoked_at, revoked_by_account_id, "
                        + "consumed_at, consumed_by_account_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), ?, ?, ?, ?, ?, UUID_TO_BIN(?), ?, UUID_TO_BIN(?))",
                "00000000-0000-4000-8000-000000001510",
                TEAM_ID,
                SECOND_MEMBER_ID,
                FIRST_ACCOUNT_ID,
                "2".repeat(64),
                "3".repeat(64),
                NOW,
                NOW.plusSeconds(24 * 60 * 60),
                NOW.plusSeconds(60),
                FIRST_ACCOUNT_ID,
                NOW.plusSeconds(60),
                SECOND_ACCOUNT_ID
        );
    }

    private void verifyOfficialSpringSessionTables(JdbcTemplate jdbcTemplate) {
        List<String> sessionColumns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'SPRING_SESSION' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class
        );
        assertThat(sessionColumns).containsExactly(
                "PRIMARY_ID",
                "SESSION_ID",
                "CREATION_TIME",
                "LAST_ACCESS_TIME",
                "MAX_INACTIVE_INTERVAL",
                "EXPIRY_TIME",
                "PRINCIPAL_NAME"
        );

        jdbcTemplate.update(
                "INSERT INTO SPRING_SESSION ("
                        + "PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, "
                        + "MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                "primary-session-id",
                "public-session-id",
                1L,
                1L,
                1800,
                1_800_001L,
                FIRST_ACCOUNT_ID
        );
        jdbcTemplate.update(
                "INSERT INTO SPRING_SESSION_ATTRIBUTES ("
                        + "SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES"
                        + ") VALUES (?, ?, ?)",
                "primary-session-id",
                "authenticatedAccount",
                new byte[]{1, 2, 3}
        );
        jdbcTemplate.update(
                "DELETE FROM SPRING_SESSION WHERE PRIMARY_ID = ?",
                "primary-session-id"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES",
                Integer.class
        )).isZero();
    }
}
