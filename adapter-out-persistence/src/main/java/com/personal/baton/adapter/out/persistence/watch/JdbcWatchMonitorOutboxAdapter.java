package com.personal.baton.adapter.out.persistence.watch;

import com.personal.baton.application.watch.WatchMonitorCandidate;
import com.personal.baton.application.watch.WatchMonitorChange;
import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWatchMonitorOutboxAdapter implements WatchMonitorOutboxPort {

    private static final String PROCESSING = "PROCESSING";

    private final JdbcTemplate jdbcTemplate;

    public JdbcWatchMonitorOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public boolean appendIfChanged(WatchMonitorChange change) {
        Objects.requireNonNull(change, "WATCH 변경 snapshot은 필수입니다");
        lockRoleResource(change.resourceId());

        return appendIfChangedAfterLock(change);
    }

    @Override
    @Transactional
    public boolean appendReconciledIfCurrent(
            WatchMonitorCandidate expectedCandidate,
            WatchMonitorChange change
    ) {
        Objects.requireNonNull(expectedCandidate, "WATCH 예상 reconciliation 후보는 필수입니다");
        Objects.requireNonNull(change, "WATCH reconciliation snapshot은 필수입니다");
        if (!expectedCandidate.resourceId().equals(change.resourceId())) {
            throw new IllegalArgumentException("WATCH reconciliation 후보와 snapshot의 자료가 다릅니다");
        }
        lockRoleResource(change.resourceId());
        if (!findCurrentCandidate(change.resourceId()).filter(expectedCandidate::equals).isPresent()) {
            return false;
        }

        return appendIfChangedAfterLock(change);
    }

    private boolean appendIfChangedAfterLock(WatchMonitorChange change) {

        Optional<Snapshot> latest = findLatestSnapshot(change.resourceId());
        if (latest.isPresent() && latest.get().hasSamePayload(change)) {
            return false;
        }
        return insertSnapshot(change) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WatchMonitorDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("WATCH claim batchSize는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(claimedAt, "WATCH claim 시각은 필수입니다");
        Objects.requireNonNull(leaseDuration, "WATCH lease 기간은 필수입니다");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("WATCH lease 기간은 양수여야 합니다");
        }

        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    candidate.id,
                    BIN_TO_UUID(candidate.event_id) AS event_id,
                    BIN_TO_UUID(candidate.resource_id) AS resource_id,
                    candidate.resource_reference,
                    candidate.monitoring_state,
                    candidate.target_url,
                    candidate.attempt_count
                FROM watch_monitor_outbox candidate
                WHERE (
                    (candidate.delivery_status = 'PENDING' AND candidate.available_at <= ?)
                    OR (
                        candidate.delivery_status = 'PROCESSING'
                        AND candidate.lease_expires_at <= ?
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM watch_monitor_outbox predecessor
                    WHERE predecessor.resource_id = candidate.resource_id
                    AND predecessor.id < candidate.id
                    AND predecessor.delivery_status IN ('PENDING', 'PROCESSING')
                )
                ORDER BY candidate.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("event_id")),
                        UUID.fromString(resultSet.getString("resource_id")),
                        resultSet.getString("resource_reference"),
                        WatchMonitoringState.valueOf(resultSet.getString("monitoring_state")),
                        resultSet.getString("target_url"),
                        resultSet.getInt("attempt_count")
                ),
                utc(claimedAt),
                utc(claimedAt),
                batchSize
        );

        LocalDateTime leaseExpiresAt = utc(claimedAt.plus(leaseDuration));
        List<WatchMonitorDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE watch_monitor_outbox
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
                    candidate.sourceRevision()
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
            long sourceRevision,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE watch_monitor_outbox
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
                utc(Objects.requireNonNull(deliveredAt, "WATCH 전달 완료 시각은 필수입니다")),
                boundedCode(resultCode, "WATCH 전달 결과 코드"),
                sourceRevision,
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            long sourceRevision,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE watch_monitor_outbox
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
                utc(Objects.requireNonNull(availableAt, "WATCH 재시도 가능 시각은 필수입니다")),
                boundedCode(errorCode, "WATCH 재시도 오류 코드"),
                sourceRevision,
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long sourceRevision,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE watch_monitor_outbox
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
                utc(Objects.requireNonNull(failedAt, "WATCH 전달 실패 시각은 필수입니다")),
                boundedCode(errorCode, "WATCH 전달 오류 코드"),
                sourceRevision,
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markInvalidTargetAndAppendInactive(
            long sourceRevision,
            UUID leaseToken,
            UUID compensationEventId,
            Instant failedAt
    ) {
        requiredLeaseToken(leaseToken);
        Objects.requireNonNull(compensationEventId, "WATCH 보상 eventId는 필수입니다");
        Objects.requireNonNull(failedAt, "WATCH 유효하지 않은 대상 처리 시각은 필수입니다");

        Optional<SourceIdentity> identity = findSourceIdentity(sourceRevision);
        if (identity.isEmpty()) {
            return false;
        }
        lockRoleResource(identity.get().resourceId());
        Optional<InvalidTargetSource> source = findInvalidTargetSourceForUpdate(sourceRevision);
        if (source.isEmpty()
                || !PROCESSING.equals(source.get().deliveryStatus())
                || !leaseToken.equals(source.get().leaseToken())) {
            return false;
        }

        int marked = jdbcTemplate.update(
                """
                UPDATE watch_monitor_outbox
                SET delivery_status = 'FAILED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    result_code = NULL,
                    last_error_code = 'INVALID_TARGET_URL'
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(failedAt),
                sourceRevision,
                leaseToken.toString()
        );
        if (marked != 1) {
            return false;
        }

        InvalidTargetSource invalidSource = source.get();
        if (invalidSource.monitoringState() == WatchMonitoringState.ACTIVE
                && !hasNewerSnapshot(invalidSource.resourceId(), sourceRevision)) {
            insertCompensationSnapshot(new WatchMonitorChange(
                    compensationEventId,
                    invalidSource.resourceId(),
                    invalidSource.resourceReference(),
                    WatchMonitoringState.INACTIVE,
                    null,
                    failedAt
            ), sourceRevision);
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueOperationalFailures(Instant availableAt) {
        return jdbcTemplate.update(
                """
                UPDATE watch_monitor_outbox
                SET delivery_status = 'PENDING',
                    available_at = ?,
                    completed_at = NULL,
                    result_code = NULL
                WHERE delivery_status = 'FAILED'
                AND (
                    last_error_code LIKE 'HTTP_3__'
                    OR last_error_code LIKE 'HTTP_4__'
                )
                AND last_error_code NOT IN ('HTTP_409', 'HTTP_422')
                """,
                utc(Objects.requireNonNull(
                        availableAt,
                        "WATCH 운영 실패 재처리 가능 시각은 필수입니다"
                ))
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMismatchedResourceReferencePrefix(String expectedPrefix) {
        Objects.requireNonNull(expectedPrefix, "WATCH resource reference prefix는 필수입니다");
        Boolean mismatched = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM watch_monitor_outbox
                    WHERE BINARY LEFT(resource_reference, ?) <> BINARY ?
                )
                """,
                Boolean.class,
                expectedPrefix.length(),
                expectedPrefix
        );
        return Boolean.TRUE.equals(mismatched);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchMonitorCandidate> findReconciliationCandidates() {
        return jdbcTemplate.query(
                """
                SELECT
                    BIN_TO_UUID(resource_record.id) AS resource_id,
                    resource_record.url AS target_url,
                    season.ended_at IS NOT NULL AS season_ended
                FROM role_resources resource_record
                JOIN roles role_record
                    ON role_record.id = resource_record.role_id
                JOIN seasons season
                    ON season.id = role_record.season_id
                ORDER BY resource_record.id
                """,
                (resultSet, rowNumber) -> new WatchMonitorCandidate(
                        UUID.fromString(resultSet.getString("resource_id")),
                        resultSet.getString("target_url"),
                        resultSet.getBoolean("season_ended")
                )
        );
    }

    private void lockRoleResource(UUID resourceId) {
        jdbcTemplate.queryForList(
                "SELECT id FROM role_resources WHERE id = UUID_TO_BIN(?) FOR UPDATE",
                resourceId.toString()
        );
    }

    private Optional<Snapshot> findLatestSnapshot(UUID resourceId) {
        List<Snapshot> snapshots = jdbcTemplate.query(
                """
                SELECT
                    latest.resource_reference,
                    latest.monitoring_state,
                    latest.target_url,
                    rejected.target_url AS rejected_target_url
                FROM watch_monitor_outbox latest
                LEFT JOIN watch_monitor_outbox rejected
                    ON rejected.id = latest.compensation_for_id
                WHERE latest.resource_id = UUID_TO_BIN(?)
                ORDER BY latest.id DESC
                LIMIT 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Snapshot(
                        resultSet.getString("resource_reference"),
                        WatchMonitoringState.valueOf(resultSet.getString("monitoring_state")),
                        resultSet.getString("target_url"),
                        resultSet.getString("rejected_target_url")
                ),
                resourceId.toString()
        );
        return snapshots.stream().findFirst();
    }

    private Optional<WatchMonitorCandidate> findCurrentCandidate(UUID resourceId) {
        List<WatchMonitorCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    BIN_TO_UUID(resource_record.id) AS resource_id,
                    resource_record.url AS target_url,
                    season.ended_at IS NOT NULL AS season_ended
                FROM role_resources resource_record
                JOIN roles role_record
                    ON role_record.id = resource_record.role_id
                JOIN seasons season
                    ON season.id = role_record.season_id
                WHERE resource_record.id = UUID_TO_BIN(?)
                """,
                (resultSet, rowNumber) -> new WatchMonitorCandidate(
                        UUID.fromString(resultSet.getString("resource_id")),
                        resultSet.getString("target_url"),
                        resultSet.getBoolean("season_ended")
                ),
                resourceId.toString()
        );
        return candidates.stream().findFirst();
    }

    private int insertSnapshot(WatchMonitorChange change) {
        return insertSnapshot(change, null);
    }

    private int insertCompensationSnapshot(
            WatchMonitorChange change,
            long compensationForRevision
    ) {
        return insertSnapshot(change, compensationForRevision);
    }

    private int insertSnapshot(WatchMonitorChange change, Long compensationForRevision) {
        return jdbcTemplate.update(
                """
                INSERT INTO watch_monitor_outbox (
                    event_id,
                    resource_id,
                    resource_reference,
                    monitoring_state,
                    target_url,
                    compensation_for_id,
                    occurred_at,
                    available_at
                ) VALUES (
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                change.eventId().toString(),
                change.resourceId().toString(),
                change.resourceReference(),
                change.monitoringState().name(),
                change.targetUrl(),
                compensationForRevision,
                utc(change.occurredAt()),
                utc(change.occurredAt())
        );
    }

    private Optional<SourceIdentity> findSourceIdentity(long sourceRevision) {
        List<SourceIdentity> identities = jdbcTemplate.query(
                """
                SELECT BIN_TO_UUID(resource_id) AS resource_id
                FROM watch_monitor_outbox
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new SourceIdentity(
                        UUID.fromString(resultSet.getString("resource_id"))
                ),
                sourceRevision
        );
        return identities.stream().findFirst();
    }

    private Optional<InvalidTargetSource> findInvalidTargetSourceForUpdate(long sourceRevision) {
        List<InvalidTargetSource> sources = jdbcTemplate.query(
                """
                SELECT
                    BIN_TO_UUID(resource_id) AS resource_id,
                    resource_reference,
                    monitoring_state,
                    delivery_status,
                    BIN_TO_UUID(lease_token) AS lease_token
                FROM watch_monitor_outbox
                WHERE id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new InvalidTargetSource(
                        UUID.fromString(resultSet.getString("resource_id")),
                        resultSet.getString("resource_reference"),
                        WatchMonitoringState.valueOf(resultSet.getString("monitoring_state")),
                        resultSet.getString("delivery_status"),
                        uuidOrNull(resultSet.getString("lease_token"))
                ),
                sourceRevision
        );
        return sources.stream().findFirst();
    }

    private boolean hasNewerSnapshot(UUID resourceId, long sourceRevision) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM watch_monitor_outbox
                WHERE resource_id = UUID_TO_BIN(?)
                AND id > ?
                """,
                Long.class,
                resourceId.toString(),
                sourceRevision
        );
        return count != null && count > 0;
    }

    private static UUID requiredLeaseToken(UUID leaseToken) {
        return Objects.requireNonNull(leaseToken, "WATCH lease token은 필수입니다");
    }

    private static String boundedCode(String code, String fieldName) {
        if (code != null && code.length() > 64) {
            throw new IllegalArgumentException(fieldName + "는 64자 이하여야 합니다");
        }
        return code;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record Snapshot(
            String resourceReference,
            WatchMonitoringState monitoringState,
            String targetUrl,
            String rejectedTargetUrl
    ) {

        private boolean hasSamePayload(WatchMonitorChange change) {
            if (!resourceReference.equals(change.resourceReference())) {
                return false;
            }
            if (monitoringState == change.monitoringState()
                    && Objects.equals(targetUrl, change.targetUrl())) {
                return true;
            }
            return rejectedTargetUrl != null
                    && change.monitoringState() == WatchMonitoringState.ACTIVE
                    && rejectedTargetUrl.equals(change.targetUrl());
        }
    }

    private record ClaimCandidate(
            long sourceRevision,
            UUID eventId,
            UUID resourceId,
            String resourceReference,
            WatchMonitoringState monitoringState,
            String targetUrl,
            int attemptCount
    ) {

        private WatchMonitorDelivery toDelivery(UUID leaseToken) {
            return new WatchMonitorDelivery(
                    sourceRevision,
                    eventId,
                    resourceId,
                    resourceReference,
                    monitoringState,
                    targetUrl,
                    attemptCount + 1,
                    leaseToken
            );
        }
    }

    private record SourceIdentity(UUID resourceId) {
    }

    private record InvalidTargetSource(
            UUID resourceId,
            String resourceReference,
            WatchMonitoringState monitoringState,
            String deliveryStatus,
            UUID leaseToken
    ) {
    }
}
