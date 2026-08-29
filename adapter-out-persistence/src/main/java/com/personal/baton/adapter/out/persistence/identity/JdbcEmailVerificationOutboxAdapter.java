package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.EmailVerificationOutboxDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcEmailVerificationOutboxAdapter implements EmailVerificationOutboxPort {

    private static final int MAXIMUM_ERROR_CODE_LENGTH = 64;

    private final JdbcTemplate jdbcTemplate;

    public JdbcEmailVerificationOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void enqueueReplacingPending(
            ProtectionContext context,
            ProtectedPayload protectedPayload,
            Instant enqueuedAt
    ) {
        Objects.requireNonNull(context, "이메일 인증 payload context는 필수입니다");
        Objects.requireNonNull(protectedPayload, "이메일 인증 보호 payload는 필수입니다");
        Objects.requireNonNull(enqueuedAt, "이메일 인증 outbox 생성 시각은 필수입니다");
        if (!context.expiresAt().isAfter(enqueuedAt)) {
            throw new IllegalArgumentException("이메일 인증 만료 시각은 생성 시각보다 뒤여야 합니다");
        }
        IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "이메일 인증 전달 요청을 일시적으로 저장할 수 없습니다",
                () -> {
                    UUID storedAccountId = lockIdentityAccount(context.identityId());
                    if (!storedAccountId.equals(context.accountId())) {
                        throw new IllegalArgumentException(
                                "이메일 인증 identity와 Account가 일치하지 않습니다"
                        );
                    }

                    supersedePendingAfterIdentityLock(context.identityId(), enqueuedAt);
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
                            context.identityId().toString(),
                            protectedPayload.ciphertext(),
                            protectedPayload.nonce(),
                            context.challengeTokenHash(),
                            utc(context.expiresAt()),
                            utc(enqueuedAt),
                            utc(enqueuedAt)
                    );
                }
        );
    }

    @Override
    @Transactional
    public int supersedePending(UUID identityId, Instant supersededAt) {
        Objects.requireNonNull(identityId, "이메일 인증 identity ID는 필수입니다");
        Objects.requireNonNull(supersededAt, "이메일 인증 supersede 시각은 필수입니다");
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "이메일 인증 전달 요청을 일시적으로 갱신할 수 없습니다",
                () -> {
                    lockIdentityAccount(identityId);
                    return supersedePendingAfterIdentityLock(identityId, supersededAt);
                }
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EmailVerificationOutboxDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("이메일 인증 claim batchSize는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(claimedAt, "이메일 인증 claim 시각은 필수입니다");
        Objects.requireNonNull(leaseDuration, "이메일 인증 lease 기간은 필수입니다");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("이메일 인증 lease 기간은 양수여야 합니다");
        }

        expireUndeliverable(claimedAt);
        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    candidate.id,
                    (
                        SELECT BIN_TO_UUID(identity_record.account_id)
                        FROM account_identities identity_record
                        WHERE identity_record.id = candidate.identity_id
                    ) AS account_id,
                    BIN_TO_UUID(candidate.identity_id) AS identity_id,
                    candidate.payload_ciphertext,
                    candidate.payload_nonce,
                    candidate.challenge_token_hash,
                    candidate.expires_at,
                    candidate.attempt_count
                FROM email_verification_delivery_outbox candidate
                WHERE (
                    (candidate.delivery_status = 'PENDING' AND candidate.available_at <= ?)
                    OR (
                        candidate.delivery_status = 'PROCESSING'
                        AND candidate.lease_expires_at <= ?
                    )
                )
                AND candidate.expires_at > ?
                AND EXISTS (
                    SELECT 1
                    FROM account_identities identity_record
                    JOIN email_verification_challenges challenge
                      ON challenge.identity_id = identity_record.id
                    WHERE identity_record.id = candidate.identity_id
                      AND identity_record.email_verified = FALSE
                      AND challenge.consumed_at IS NULL
                      AND challenge.token_hash = candidate.challenge_token_hash
                )
                ORDER BY candidate.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("identity_id")),
                        UUID.fromString(resultSet.getString("account_id")),
                        resultSet.getString("challenge_token_hash"),
                        resultSet.getString("payload_ciphertext"),
                        resultSet.getString("payload_nonce"),
                        resultSet.getTimestamp("expires_at").toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                        resultSet.getInt("attempt_count")
                ),
                utc(claimedAt),
                utc(claimedAt),
                utc(claimedAt),
                batchSize
        );

        LocalDateTime leaseExpiresAt = utc(claimedAt.plus(leaseDuration));
        List<EmailVerificationOutboxDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE email_verification_delivery_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?,
                        completed_at = NULL,
                        last_error_code = NULL
                    WHERE id = ?
                    """,
                    leaseToken.toString(),
                    leaseExpiresAt,
                    candidate.deliveryId()
            );
            if (updated == 1) {
                deliveries.add(candidate.toDelivery(leaseToken));
            }
        }
        return List.copyOf(deliveries);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isClaimCurrent(long deliveryId, UUID leaseToken, Instant checkedAt) {
        Boolean current = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM email_verification_delivery_outbox delivery
                    JOIN account_identities identity_record
                      ON identity_record.id = delivery.identity_id
                    JOIN email_verification_challenges challenge
                      ON challenge.identity_id = delivery.identity_id
                    WHERE delivery.id = ?
                      AND delivery.delivery_status = 'PROCESSING'
                      AND delivery.lease_token = UUID_TO_BIN(?)
                      AND delivery.lease_expires_at > ?
                      AND delivery.expires_at > ?
                      AND identity_record.email_verified = FALSE
                      AND challenge.consumed_at IS NULL
                      AND challenge.token_hash = delivery.challenge_token_hash
                )
                """,
                Boolean.class,
                requiredDeliveryId(deliveryId),
                requiredLeaseToken(leaseToken).toString(),
                utc(Objects.requireNonNull(checkedAt, "이메일 인증 claim 확인 시각은 필수입니다")),
                utc(checkedAt)
        );
        return Boolean.TRUE.equals(current);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(long deliveryId, UUID leaseToken, Instant deliveredAt) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'DELIVERED',
                    payload_ciphertext = NULL,
                    payload_nonce = NULL,
                    challenge_token_hash = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    last_error_code = NULL
                WHERE id = ?
                  AND delivery_status = 'PROCESSING'
                  AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(deliveredAt, "이메일 인증 전달 완료 시각은 필수입니다")),
                requiredDeliveryId(deliveryId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            long deliveryId,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'PENDING',
                    available_at = ?,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = NULL,
                    last_error_code = ?
                WHERE id = ?
                  AND delivery_status = 'PROCESSING'
                  AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(availableAt, "이메일 인증 재시도 시각은 필수입니다")),
                requiredErrorCode(errorCode),
                requiredDeliveryId(deliveryId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long deliveryId,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'FAILED',
                    payload_ciphertext = NULL,
                    payload_nonce = NULL,
                    challenge_token_hash = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    last_error_code = ?
                WHERE id = ?
                  AND delivery_status = 'PROCESSING'
                  AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(failedAt, "이메일 인증 실패 시각은 필수입니다")),
                requiredErrorCode(errorCode),
                requiredDeliveryId(deliveryId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    private UUID lockIdentityAccount(UUID identityId) {
        UUID accountId = DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT BIN_TO_UUID(account_id) AS account_id
                FROM account_identities
                WHERE id = UUID_TO_BIN(?)
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("account_id")),
                identityId.toString()
        ));
        if (accountId == null) {
            throw new IllegalArgumentException("이메일 인증 identity를 찾을 수 없습니다");
        }
        return accountId;
    }

    private int supersedePendingAfterIdentityLock(UUID identityId, Instant supersededAt) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'SUPERSEDED',
                    payload_ciphertext = NULL,
                    payload_nonce = NULL,
                    challenge_token_hash = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    last_error_code = NULL
                WHERE identity_id = UUID_TO_BIN(?)
                  AND delivery_status IN ('PENDING', 'PROCESSING')
                """,
                utc(supersededAt),
                identityId.toString()
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireUndeliverable(Instant expiredAt) {
        Objects.requireNonNull(expiredAt, "이메일 인증 outbox 만료 처리 시각은 필수입니다");
        return jdbcTemplate.update(
                """
                UPDATE email_verification_delivery_outbox
                SET delivery_status = 'FAILED',
                    payload_ciphertext = NULL,
                    payload_nonce = NULL,
                    challenge_token_hash = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    last_error_code = 'VERIFICATION_TOKEN_EXPIRED'
                WHERE expires_at <= ?
                  AND (
                    delivery_status = 'PENDING'
                    OR (
                        delivery_status = 'PROCESSING'
                        AND lease_expires_at <= ?
                    )
                  )
                """,
                utc(expiredAt),
                utc(expiredAt),
                utc(expiredAt)
        );
    }

    private long requiredDeliveryId(long deliveryId) {
        if (deliveryId < 1) {
            throw new IllegalArgumentException("이메일 인증 outbox ID는 1 이상이어야 합니다");
        }
        return deliveryId;
    }

    private UUID requiredLeaseToken(UUID leaseToken) {
        return Objects.requireNonNull(leaseToken, "이메일 인증 lease token은 필수입니다");
    }

    private String requiredErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("이메일 인증 전달 오류 코드는 필수입니다");
        }
        return errorCode.length() <= MAXIMUM_ERROR_CODE_LENGTH
                ? errorCode
                : errorCode.substring(0, MAXIMUM_ERROR_CODE_LENGTH);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ClaimCandidate(
            long deliveryId,
            UUID identityId,
            UUID accountId,
            String challengeTokenHash,
            String ciphertext,
            String nonce,
            Instant expiresAt,
            int previousAttemptCount
    ) {

        private EmailVerificationOutboxDelivery toDelivery(UUID leaseToken) {
            return new EmailVerificationOutboxDelivery(
                    deliveryId,
                    new ProtectionContext(
                            identityId,
                            accountId,
                            challengeTokenHash,
                            expiresAt
                    ),
                    new ProtectedPayload(ciphertext, nonce),
                    previousAttemptCount + 1,
                    leaseToken
            );
        }
    }
}
