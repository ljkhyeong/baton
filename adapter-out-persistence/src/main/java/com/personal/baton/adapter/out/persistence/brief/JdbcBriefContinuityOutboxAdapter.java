package com.personal.baton.adapter.out.persistence.brief;

import static com.personal.baton.adapter.out.persistence.LeasedOutboxTable.utc;

import com.personal.baton.adapter.out.persistence.LeasedOutboxTable;
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
    private final LeasedOutboxTable outbox;

    public JdbcBriefContinuityOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.outbox = new LeasedOutboxTable(jdbcTemplate, "brief_continuity_outbox");
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

        Instant leaseExpiresAt = claimedAt.plus(leaseDuration);
        List<BriefContinuityDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            if (outbox.lease(candidate.outboxId(), leaseToken, leaseExpiresAt)) {
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
        return outbox.markDelivered(outboxId, leaseToken, deliveredAt, resultCode);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            long outboxId,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return outbox.markRetry(outboxId, leaseToken, availableAt, errorCode);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long outboxId,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return outbox.markFailed(outboxId, leaseToken, failedAt, errorCode);
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
