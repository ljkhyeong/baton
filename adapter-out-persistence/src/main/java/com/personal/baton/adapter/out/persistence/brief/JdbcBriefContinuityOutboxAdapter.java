package com.personal.baton.adapter.out.persistence.brief;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import com.personal.baton.application.brief.BriefContinuityEvent;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBriefContinuityOutboxAdapter implements BriefContinuityOutboxPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBriefContinuityOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<BriefContinuityDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("BRIEF claim batchSize는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(claimedAt, "BRIEF claim 시각은 필수입니다");
        Objects.requireNonNull(leaseDuration, "BRIEF lease 기간은 필수입니다");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("BRIEF lease 기간은 양수여야 합니다");
        }

        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    candidate.id,
                    BIN_TO_UUID(candidate.event_id) AS event_id,
                    candidate.event_type,
                    candidate.event_version,
                    candidate.source_severity,
                    BIN_TO_UUID(candidate.workspace_id) AS workspace_id,
                    BIN_TO_UUID(candidate.season_id) AS season_id,
                    candidate.source_reference,
                    candidate.aggregate_revision,
                    candidate.occurred_at,
                    candidate.event_state,
                    candidate.attempt_count
                FROM brief_continuity_outbox candidate
                WHERE (
                    (candidate.delivery_status = 'PENDING' AND candidate.available_at <= ?)
                    OR (
                        candidate.delivery_status = 'PROCESSING'
                        AND candidate.lease_expires_at <= ?
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM brief_continuity_outbox predecessor
                    WHERE predecessor.signal_id = candidate.signal_id
                    AND predecessor.aggregate_revision < candidate.aggregate_revision
                    AND predecessor.delivery_status IN ('PENDING', 'PROCESSING')
                )
                ORDER BY candidate.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getLong("id"),
                        new BriefContinuityEvent(
                                UUID.fromString(resultSet.getString("event_id")),
                                ContinuitySignalType.valueOf(resultSet.getString("event_type")),
                                resultSet.getInt("event_version"),
                                ContinuitySignalSeverity.valueOf(
                                        resultSet.getString("source_severity")
                                ),
                                UUID.fromString(resultSet.getString("workspace_id")),
                                UUID.fromString(resultSet.getString("season_id")),
                                resultSet.getString("source_reference"),
                                resultSet.getLong("aggregate_revision"),
                                resultSet.getObject("occurred_at", LocalDateTime.class)
                                        .toInstant(ZoneOffset.UTC),
                                BriefContinuityEvent.State.valueOf(
                                        resultSet.getString("event_state")
                                )
                        ),
                        resultSet.getInt("attempt_count")
                ),
                utc(claimedAt),
                utc(claimedAt),
                batchSize
        );

        LocalDateTime leaseExpiresAt = utc(claimedAt.plus(leaseDuration));
        List<BriefContinuityDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE brief_continuity_outbox
                    SET delivery_status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?,
                        completed_at = NULL,
                        result_code = NULL
                    WHERE id = ?
                    """,
                    leaseToken.toString(),
                    leaseExpiresAt,
                    candidate.outboxId()
            );
            if (updated == 1) {
                deliveries.add(candidate.toDelivery(leaseToken));
            }
        }
        return List.copyOf(deliveries);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(
            long outboxId,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET delivery_status = 'DELIVERED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    result_code = ?,
                    last_error_code = NULL
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(deliveredAt, "BRIEF 전달 완료 시각은 필수입니다")),
                requiredCode(resultCode, "BRIEF 전달 결과 코드"),
                requiredOutboxId(outboxId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            long outboxId,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET delivery_status = 'PENDING',
                    available_at = ?,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = NULL,
                    result_code = NULL,
                    last_error_code = ?
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(availableAt, "BRIEF 재시도 가능 시각은 필수입니다")),
                requiredCode(errorCode, "BRIEF 재시도 오류 코드"),
                requiredOutboxId(outboxId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long outboxId,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET delivery_status = 'FAILED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    result_code = NULL,
                    last_error_code = ?
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(failedAt, "BRIEF 전달 실패 시각은 필수입니다")),
                requiredCode(errorCode, "BRIEF 전달 오류 코드"),
                requiredOutboxId(outboxId),
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    private static long requiredOutboxId(long outboxId) {
        if (outboxId < 1) {
            throw new IllegalArgumentException("BRIEF outbox ID는 양수여야 합니다");
        }
        return outboxId;
    }

    private static UUID requiredLeaseToken(UUID leaseToken) {
        return Objects.requireNonNull(leaseToken, "BRIEF lease token은 필수입니다");
    }

    private static String requiredCode(String code, String name) {
        Objects.requireNonNull(code, name + "는 필수입니다");
        if (code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException(name + "는 공백이 아닌 64자 이하여야 합니다");
        }
        return code;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ClaimCandidate(
            long outboxId,
            BriefContinuityEvent event,
            int attemptCount
    ) {

        private BriefContinuityDelivery toDelivery(UUID leaseToken) {
            return new BriefContinuityDelivery(
                    outboxId,
                    event,
                    attemptCount + 1,
                    leaseToken
            );
        }
    }
}
