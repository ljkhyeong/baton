package com.personal.baton.application.identity;

import java.time.LocalDateTime;
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
class AccountIdentityMigrationTest {

    private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000001901";
    private static final String OTHER_ACCOUNT_ID = "00000000-0000-4000-8000-000000001902";
    private static final String GOOGLE_IDENTITY_ID = "00000000-0000-4000-8000-000000001903";
    private static final String NAVER_IDENTITY_ID = "00000000-0000-4000-8000-000000001904";
    private static final String LOCAL_IDENTITY_ID = "00000000-0000-4000-8000-000000001905";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 8, 1, 2, 3);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_account_identity_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V19은 공급자 subject 유일성과 검증 전에는 자격이 없어도 되는 자체 이메일 경계를 추가한다")
    @Test
    void addsProviderNeutralIdentityConstraints() {
        migrateTo("18");
        migrateTo("19");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        insertAccount(jdbcTemplate, ACCOUNT_ID, "첫 계정");
        insertAccount(jdbcTemplate, OTHER_ACCOUNT_ID, "두 번째 계정");

        insertIdentity(
                jdbcTemplate,
                GOOGLE_IDENTITY_ID,
                ACCOUNT_ID,
                "GOOGLE",
                "google-subject",
                "same@example.com",
                true
        );
        insertIdentity(
                jdbcTemplate,
                NAVER_IDENTITY_ID,
                OTHER_ACCOUNT_ID,
                "NAVER",
                "naver-subject",
                "same@example.com",
                true
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_identities WHERE email_snapshot = 'same@example.com'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT account_id) FROM account_identities "
                        + "WHERE email_snapshot = 'same@example.com'",
                Integer.class
        )).isEqualTo(2);

        assertThatThrownBy(() -> insertIdentity(
                jdbcTemplate,
                "00000000-0000-4000-8000-000000001906",
                OTHER_ACCOUNT_ID,
                "GOOGLE",
                "google-subject",
                "other@example.com",
                true
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertIdentity(
                jdbcTemplate,
                "00000000-0000-4000-8000-000000001907",
                ACCOUNT_ID,
                "GOOGLE",
                "different-google-subject",
                "other@example.com",
                true
        )).isInstanceOf(DataAccessException.class);

        insertIdentity(
                jdbcTemplate,
                LOCAL_IDENTITY_ID,
                ACCOUNT_ID,
                "LOCAL_EMAIL",
                "study.user@example.com",
                "study.user@example.com",
                false
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_credentials WHERE identity_id = UUID_TO_BIN(?)",
                Integer.class,
                LOCAL_IDENTITY_ID
        )).isZero();
        jdbcTemplate.update(
                "UPDATE account_identities SET email_verified = TRUE "
                        + "WHERE id = UUID_TO_BIN(?)",
                LOCAL_IDENTITY_ID
        );
        jdbcTemplate.update(
                """
                INSERT INTO local_credentials (
                    identity_id, password_hash, created_at, updated_at
                ) VALUES (UUID_TO_BIN(?), ?, ?, ?)
                """,
                LOCAL_IDENTITY_ID,
                "{bcrypt}$2a$10$opaque-encoded-password-value",
                CREATED_AT,
                CREATED_AT
        );
        jdbcTemplate.update(
                """
                INSERT INTO email_verification_challenges (
                    id, identity_id, token_hash, expires_at, created_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?, ?, ?)
                """,
                LOCAL_IDENTITY_ID,
                "a".repeat(64),
                CREATED_AT.plusMinutes(30),
                CREATED_AT
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM local_credentials WHERE identity_id = UUID_TO_BIN(?)",
                String.class,
                LOCAL_IDENTITY_ID
        )).isEqualTo("{bcrypt}$2a$10$opaque-encoded-password-value");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM email_verification_challenges WHERE identity_id = UUID_TO_BIN(?)",
                String.class,
                LOCAL_IDENTITY_ID
        )).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> insertIdentity(
                jdbcTemplate,
                "00000000-0000-4000-8000-000000001908",
                OTHER_ACCOUNT_ID,
                "LOCAL_EMAIL",
                "UPPER@example.com",
                "UPPER@example.com",
                false
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO email_verification_challenges (
                    id, identity_id, token_hash, expires_at, created_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?, ?, ?)
                """,
                LOCAL_IDENTITY_ID,
                "b".repeat(64),
                CREATED_AT.plusMinutes(30),
                CREATED_AT
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO email_verification_challenges (
                    id, identity_id, token_hash, expires_at, created_at
                ) VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?, ?, ?)
                """,
                GOOGLE_IDENTITY_ID,
                "not-a-sha256-hash",
                CREATED_AT.plusMinutes(30),
                CREATED_AT
        )).isInstanceOf(DataAccessException.class);

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

    private void insertAccount(JdbcTemplate jdbcTemplate, String accountId, String displayName) {
        jdbcTemplate.update(
                """
                INSERT INTO accounts (id, display_name, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?)
                """,
                accountId,
                displayName,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void insertIdentity(
            JdbcTemplate jdbcTemplate,
            String identityId,
            String accountId,
            String provider,
            String providerSubject,
            String email,
            boolean emailVerified
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO account_identities (
                    id,
                    account_id,
                    provider,
                    provider_subject,
                    email_snapshot,
                    email_verified,
                    created_at,
                    last_authenticated_at
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)
                """,
                identityId,
                accountId,
                provider,
                providerSubject,
                email,
                emailVerified,
                CREATED_AT,
                provider.equals("LOCAL_EMAIL") ? null : CREATED_AT
        );
    }
}
