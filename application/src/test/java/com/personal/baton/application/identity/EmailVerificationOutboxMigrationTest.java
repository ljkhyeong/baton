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
class EmailVerificationOutboxMigrationTest {

    private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000002101";
    private static final String IDENTITY_ID = "00000000-0000-4000-8000-000000002102";
    private static final String TOKEN = "secure-email-verification-token-000000000001";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 8, 1, 2, 3);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_email_verification_outbox_migration")
            .withUsername("baton")
            .withPassword("password");

    @DisplayName("V21은 identity에 귀속된 lease 기반 이메일 인증 전달 outbox를 추가한다")
    @Test
    void addsDurableEmailVerificationDeliveryOutbox() {
        migrateTo("20");
        migrateTo("21");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        insertIdentityFixture(jdbcTemplate);

        jdbcTemplate.update(
                """
                INSERT INTO email_verification_delivery_outbox (
                    identity_id,
                    payload_ciphertext,
                    payload_nonce,
                    challenge_token_hash,
                    expires_at,
                    delivery_status,
                    attempt_count,
                    available_at,
                    created_at
                ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """,
                IDENTITY_ID,
                "encryptedPayloadValue000000000000000000000000000000",
                "nonceValue000000",
                VerificationTokenHash.hash(TOKEN),
                CREATED_AT.plusMinutes(30),
                CREATED_AT,
                CREATED_AT
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload_ciphertext FROM email_verification_delivery_outbox",
                String.class
        )).doesNotContain(TOKEN, "study.user@example.com");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'email_verification_delivery_outbox' "
                        + "AND column_name IN ('email', 'verification_token')",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM email_verification_delivery_outbox",
                String.class
        )).isEqualTo("PENDING");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO email_verification_delivery_outbox (
                    identity_id,
                    payload_ciphertext,
                    payload_nonce,
                    challenge_token_hash,
                    expires_at,
                    delivery_status,
                    attempt_count,
                    available_at,
                    created_at
                ) VALUES (UUID_TO_BIN(?), '***', ?, ?, ?, 'PENDING', 0, ?, ?)
                """,
                IDENTITY_ID,
                "nonceValue000000",
                VerificationTokenHash.hash(TOKEN),
                CREATED_AT.plusMinutes(30),
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'PROCESSING'
                WHERE id = 1
                """
        )).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '21'",
                Boolean.class
        )).isTrue();
    }

    private void insertIdentityFixture(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                """
                INSERT INTO accounts (id, display_name, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), 'Outbox 사용자', ?, ?)
                """,
                ACCOUNT_ID,
                CREATED_AT,
                CREATED_AT
        );
        jdbcTemplate.update(
                """
                INSERT INTO account_identities (
                    id,
                    account_id,
                    provider,
                    provider_subject,
                    email_snapshot,
                    email_verified,
                    created_at
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'LOCAL_EMAIL', ?, ?, FALSE, ?)
                """,
                IDENTITY_ID,
                ACCOUNT_ID,
                "study.user@example.com",
                "study.user@example.com",
                CREATED_AT
        );
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
}
