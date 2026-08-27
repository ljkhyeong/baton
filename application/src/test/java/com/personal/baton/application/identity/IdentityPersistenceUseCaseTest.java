package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.DispatchEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.in.ExpireEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase.RegisterLocalAccountCommand;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginCommand;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase.VerifyLocalEmailCommand;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort.EmailVerificationDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import com.personal.baton.domain.identity.IdentityProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H",
                "baton.identity.email-verification.outbox-encryption-key="
                        + "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                "baton.identity.email-verification.dispatch-interval=PT24H",
                "spring.datasource.hikari.connection-init-sql="
                        + "SET SESSION innodb_lock_wait_timeout=1"
        }
)
class IdentityPersistenceUseCaseTest {

    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String REPLACEMENT_PASSWORD = "replacement battery staple";

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_identity_usecase")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private RegisterLocalAccountUseCase registerLocalAccountUseCase;

    @Autowired
    private VerifyLocalEmailUseCase verifyLocalEmailUseCase;

    @Autowired
    private LoadLocalCredentialUseCase loadLocalCredentialUseCase;

    @Autowired
    private ResolveExternalLoginUseCase resolveExternalLoginUseCase;

    @Autowired
    private DispatchEmailVerificationOutboxUseCase dispatchEmailVerificationOutboxUseCase;

    @Autowired
    private ExpireEmailVerificationOutboxUseCase expireEmailVerificationOutboxUseCase;

    @Autowired
    private EmailVerificationOutboxPort emailVerificationOutboxPort;

    @Autowired
    private EmailVerificationOutboxPayloadProtector outboxPayloadProtector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailVerificationDeliveryPort emailVerificationDeliveryPort;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM email_verification_delivery_outbox");
        jdbcTemplate.update("DELETE FROM email_verification_challenges");
        jdbcTemplate.update("DELETE FROM local_credentials");
        jdbcTemplate.update("DELETE FROM account_identities");
        jdbcTemplate.update("DELETE FROM accounts");
        reset(emailVerificationDeliveryPort);
    }

    @DisplayName("자체 이메일 가입부터 일회성 검증과 로그인 자격 조회까지 실제 MySQL transaction으로 이어진다")
    @Test
    void persistsAndVerifiesLocalCredentialLifecycle() {
        var registration = registerLocalAccountUseCase.registerLocalAccount(
                new RegisterLocalAccountCommand(
                        " Study.User@Example.COM ",
                        "스터디 사용자"
                )
        );
        String firstVerificationToken = pendingPlainPayload().verificationToken();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload_ciphertext FROM email_verification_delivery_outbox",
                String.class
        )).doesNotContain(firstVerificationToken, "study.user@example.com");
        assertThat(loadLocalCredentialUseCase.loadLocalCredential(
                "study.user@example.com"
        )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_credentials",
                Integer.class
        )).isZero();

        var repeated = registerLocalAccountUseCase.registerLocalAccount(
                new RegisterLocalAccountCommand(
                        "study.user@example.com",
                        "변경한 스터디 사용자"
                )
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_delivery_outbox "
                        + "WHERE delivery_status = 'SUPERSEDED'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_delivery_outbox "
                        + "WHERE delivery_status = 'PENDING'",
                Integer.class
        )).isOne();
        var dispatchResult = dispatchEmailVerificationOutboxUseCase.dispatchPending();
        var deliveryCaptor = ArgumentCaptor.forClass(
                EmailVerificationDelivery.class
        );
        verify(emailVerificationDeliveryPort).deliver(deliveryCaptor.capture());
        String verificationToken = deliveryCaptor.getValue().verificationToken();
        assertThat(dispatchResult.deliveredCount()).isOne();
        assertThat(verificationToken).isEqualTo(firstVerificationToken);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_delivery_outbox "
                        + "WHERE delivery_status IN ('DELIVERED', 'SUPERSEDED') "
                        + "AND payload_ciphertext IS NULL AND payload_nonce IS NULL "
                        + "AND challenge_token_hash IS NULL",
                Integer.class
        )).isOne();

        String storedVerificationHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM email_verification_challenges",
                String.class
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_credentials",
                Integer.class
        )).isZero();
        assertThat(storedVerificationHash)
                .hasSize(64)
                .doesNotContain(firstVerificationToken, verificationToken);
        assertThat(repeated.account().accountId()).isEqualTo(registration.account().accountId());
        assertThat(repeated.verificationExpiresAt())
                .isEqualTo(registration.verificationExpiresAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM accounts",
                String.class
        )).isEqualTo("스터디 사용자");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_subject FROM account_identities",
                String.class
        )).isEqualTo("study.user@example.com");
        var verification = verifyLocalEmailUseCase.verifyLocalEmail(
                new VerifyLocalEmailCommand(verificationToken, RAW_PASSWORD)
        );
        var credential = loadLocalCredentialUseCase
                .loadLocalCredential("STUDY.USER@EXAMPLE.COM")
                .orElseThrow();
        String storedPasswordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM local_credentials",
                String.class
        );

        assertThat(verification.account().accountId())
                .isEqualTo(registration.account().accountId());
        assertThat(credential.accountId()).isEqualTo(registration.account().accountId());
        assertThat(credential.emailVerified()).isTrue();
        assertThat(storedPasswordHash).startsWith("{").doesNotContain(RAW_PASSWORD);
        assertThat(credential.passwordHash()).isEqualTo(storedPasswordHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM email_verification_challenges",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_delivery_outbox "
                        + "WHERE delivery_status IN ('PENDING', 'PROCESSING')",
                Integer.class
        )).isZero();
        assertThatThrownBy(() -> verifyLocalEmailUseCase.verifyLocalEmail(
                new VerifyLocalEmailCommand(verificationToken, REPLACEMENT_PASSWORD)
        ))
                .isInstanceOf(EmailVerificationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM local_credentials",
                String.class
        )).isEqualTo(storedPasswordHash);
    }

    @DisplayName("MySQL 행 잠금 timeout은 이메일 중복 202가 아닌 재시도 가능 장애로 보존된다")
    @Test
    void preservesMySqlLockTimeoutAsTemporaryIdentityFailure() throws Exception {
        registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                "locked@example.com",
                "원래 사용자"
        ));
        UUID challengeId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM email_verification_challenges",
                String.class
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT @@SESSION.innodb_lock_wait_timeout",
                Long.class
        )).isEqualTo(1L);

        try (HeldDatabaseLock ignored = holdExclusiveRowLock(
                "SELECT HEX(id) FROM email_verification_challenges "
                        + "WHERE id = UUID_TO_BIN(?) FOR UPDATE",
                challengeId
        )) {
            assertThatThrownBy(() -> registerLocalAccountUseCase.registerLocalAccount(
                    new RegisterLocalAccountCommand(
                            "locked@example.com",
                            "잠금 중 변경 시도"
                    )
            )).isInstanceOfSatisfying(
                    IdentityOperationUnavailableException.class,
                    exception -> assertThat(exception.getCause())
                            .isInstanceOf(TransientDataAccessException.class)
            );
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM accounts",
                String.class
        )).isEqualTo("원래 사용자");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_delivery_outbox "
                        + "WHERE delivery_status = 'PENDING'",
                Integer.class
        )).isOne();
    }

    @DisplayName("이메일 인증 outbox lease는 다른 dispatcher의 동시 claim을 막고 만료 뒤 복구한다")
    @Test
    void leasesEmailVerificationOutboxAcrossDispatchers() {
        registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                "lease@example.com",
                "Lease 사용자"
        ));
        Instant claimedAt = Instant.now().plusSeconds(1);

        var firstClaim = emailVerificationOutboxPort.claimPending(
                1,
                claimedAt,
                Duration.ofMinutes(1)
        );
        var competingClaim = emailVerificationOutboxPort.claimPending(
                1,
                claimedAt,
                Duration.ofMinutes(1)
        );
        var recoveredClaim = emailVerificationOutboxPort.claimPending(
                1,
                claimedAt.plusSeconds(61),
                Duration.ofMinutes(1)
        );

        assertThat(firstClaim).singleElement()
                .extracting(EmailVerificationOutboxDelivery::attemptCount)
                .isEqualTo(1);
        assertThat(competingClaim).isEmpty();
        assertThat(recoveredClaim).singleElement()
                .extracting(EmailVerificationOutboxDelivery::attemptCount)
                .isEqualTo(2);
        assertThat(recoveredClaim.getFirst().leaseToken())
                .isNotEqualTo(firstClaim.getFirst().leaseToken());
    }

    @DisplayName("SMTP 발송 비활성 상태에서도 만료 outbox의 암호문과 nonce를 독립적으로 폐기한다")
    @Test
    void expiresProtectedPayloadWhileEmailDeliveryIsDisabled() {
        registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                "expired@example.com",
                "만료 사용자"
        ));
        LocalDateTime expiredAt = LocalDateTime.ofInstant(
                Instant.now().minusSeconds(60),
                ZoneOffset.UTC
        );
        LocalDateTime createdAt = expiredAt.minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE email_verification_delivery_outbox "
                        + "SET created_at = ?, available_at = ?, expires_at = ?",
                createdAt,
                createdAt,
                expiredAt
        );

        int expiredCount = expireEmailVerificationOutboxUseCase.expireUndeliverable();

        assertThat(expiredCount).isOne();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT delivery_status, last_error_code, payload_ciphertext, "
                        + "payload_nonce, challenge_token_hash "
                        + "FROM email_verification_delivery_outbox"
        ))
                .containsEntry("delivery_status", "FAILED")
                .containsEntry("last_error_code", "VERIFICATION_TOKEN_EXPIRED")
                .containsEntry("payload_ciphertext", null)
                .containsEntry("payload_nonce", null)
                .containsEntry("challenge_token_hash", null);
    }

    @DisplayName("MySQL에 저장된 AES-GCM ciphertext가 변조되면 SMTP 없이 fail-closed 한다")
    @Test
    void rejectsTamperedPersistedPayload() {
        registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                "tamper@example.com",
                "Tamper 사용자"
        ));
        String ciphertext = jdbcTemplate.queryForObject(
                "SELECT payload_ciphertext FROM email_verification_delivery_outbox",
                String.class
        );
        char replacement = ciphertext.endsWith("A") ? 'B' : 'A';
        String tampered = ciphertext.substring(0, ciphertext.length() - 1) + replacement;
        jdbcTemplate.update(
                "UPDATE email_verification_delivery_outbox SET payload_ciphertext = ?",
                tampered
        );

        var result = dispatchEmailVerificationOutboxUseCase.dispatchPending();

        assertThat(result.failedCount()).isOne();
        verify(emailVerificationDeliveryPort, never()).deliver(any());
        assertThat(jdbcTemplate.queryForMap(
                "SELECT delivery_status, last_error_code, payload_ciphertext, payload_nonce "
                        + "FROM email_verification_delivery_outbox"
        ))
                .containsEntry("delivery_status", "FAILED")
                .containsEntry("last_error_code", "EMAIL_PAYLOAD_INVALID")
                .containsEntry("payload_ciphertext", null)
                .containsEntry("payload_nonce", null);
    }

    @DisplayName("같은 이메일의 Google과 Naver 로그인은 별도 Account로 남는다")
    @Test
    void persistsExternalIdentityWithoutEmailAutoLinking() {
        var google = resolveExternalLoginUseCase.resolveExternalLogin(new ExternalLoginCommand(
                IdentityProvider.GOOGLE,
                "google-subject",
                "same@example.com",
                true,
                "Google 사용자"
        ));
        var naver = resolveExternalLoginUseCase.resolveExternalLogin(new ExternalLoginCommand(
                IdentityProvider.NAVER,
                "naver-subject",
                "same@example.com",
                true,
                "Naver 사용자"
        ));

        assertThat(google.created()).isTrue();
        assertThat(naver.created()).isTrue();
        assertThat(google.account().accountId()).isNotEqualTo(naver.account().accountId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT account_id) FROM account_identities "
                        + "WHERE email_snapshot = 'same@example.com'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_identities WHERE email_snapshot = ?",
                Integer.class,
                "same@example.com"
        )).isEqualTo(2);
    }

    @DisplayName("같은 provider subject의 동시 최초 OAuth callback은 한 Account로 수렴한다")
    @Test
    void convergesConcurrentFirstExternalLogin() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResolveExternalLoginUseCase.ExternalLoginResult> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                return resolveExternalLoginUseCase.resolveExternalLogin(new ExternalLoginCommand(
                        IdentityProvider.GOOGLE,
                        "concurrent-google-subject",
                        "concurrent@example.com",
                        true,
                        "동시 Google 사용자"
                ));
            });
            Future<ResolveExternalLoginUseCase.ExternalLoginResult> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                return resolveExternalLoginUseCase.resolveExternalLogin(new ExternalLoginCommand(
                        IdentityProvider.GOOGLE,
                        "concurrent-google-subject",
                        "concurrent@example.com",
                        true,
                        "동시 Google 사용자"
                ));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.account().accountId())
                    .isEqualTo(secondResult.account().accountId());
            assertThat(List.of(firstResult.created(), secondResult.created()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM accounts",
                    Integer.class
            )).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account_identities "
                            + "WHERE provider = 'GOOGLE' AND provider_subject = ?",
                    Integer.class,
                    "concurrent-google-subject"
            )).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private EmailVerificationOutboxPayloadProtector.PlainPayload pendingPlainPayload() {
        StoredPayload stored = jdbcTemplate.queryForObject(
                """
                SELECT
                    BIN_TO_UUID(delivery.identity_id) AS identity_id,
                    BIN_TO_UUID(identity_record.account_id) AS account_id,
                    delivery.challenge_token_hash,
                    delivery.expires_at,
                    delivery.payload_ciphertext,
                    delivery.payload_nonce
                FROM email_verification_delivery_outbox delivery
                JOIN account_identities identity_record
                  ON identity_record.id = delivery.identity_id
                WHERE delivery.delivery_status = 'PENDING'
                """,
                (resultSet, rowNumber) -> new StoredPayload(
                        UUID.fromString(resultSet.getString("identity_id")),
                        UUID.fromString(resultSet.getString("account_id")),
                        resultSet.getString("challenge_token_hash"),
                        resultSet.getTimestamp("expires_at").toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                        resultSet.getString("payload_ciphertext"),
                        resultSet.getString("payload_nonce")
                )
        );
        return outboxPayloadProtector.unprotect(
                new ProtectionContext(
                        stored.identityId(),
                        stored.accountId(),
                        stored.challengeTokenHash(),
                        stored.expiresAt()
                ),
                new ProtectedPayload(stored.ciphertext(), stored.nonce())
        );
    }

    private HeldDatabaseLock holdExclusiveRowLock(String sql, UUID id) throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setTimeout(10);
        Future<?> lockHolder = executor.submit(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(sql, String.class, id.toString());
                    lockAcquired.countDown();
                    await(releaseLock);
                })
        );

        if (!lockAcquired.await(10, TimeUnit.SECONDS)) {
            releaseLock.countDown();
            executor.shutdownNow();
            throw new IllegalStateException("테스트용 데이터베이스 행 잠금을 획득하지 못했습니다");
        }
        return new HeldDatabaseLock(releaseLock, lockHolder, executor);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트용 데이터베이스 행 잠금 해제를 기다리지 못했습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트용 데이터베이스 행 잠금 대기가 중단됐습니다", exception);
        }
    }

    private record StoredPayload(
            UUID identityId,
            UUID accountId,
            String challengeTokenHash,
            Instant expiresAt,
            String ciphertext,
            String nonce
    ) {
    }

    private record HeldDatabaseLock(
            CountDownLatch releaseLock,
            Future<?> lockHolder,
            ExecutorService executor
    ) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            releaseLock.countDown();
            try {
                lockHolder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트용 잠금 실행기를 종료하지 못했습니다");
                }
            }
        }
    }
}
