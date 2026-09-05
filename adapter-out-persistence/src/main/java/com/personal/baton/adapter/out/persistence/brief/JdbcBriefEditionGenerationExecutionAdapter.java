package com.personal.baton.adapter.out.persistence.brief;

import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBriefEditionGenerationExecutionAdapter
        implements BriefEditionGenerationExecutionPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBriefEditionGenerationExecutionAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DeliveryBoundary findDeliveryBoundary(UUID teamId, UUID seasonId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COALESCE(MAX(id), 0) AS watermark,
                    COALESCE(SUM(
                        CASE WHEN delivery_status IN ('PENDING', 'PROCESSING') THEN 1 ELSE 0 END
                    ), 0) AS pending_count,
                    COALESCE(SUM(CASE WHEN delivery_status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count,
                    MAX(CASE WHEN delivery_status = 'DELIVERED' THEN completed_at END) AS last_delivered_at
                FROM brief_continuity_outbox
                WHERE workspace_id = UUID_TO_BIN(?)
                AND season_id = UUID_TO_BIN(?)
                """,
                (resultSet, rowNumber) -> new DeliveryBoundary(
                        resultSet.getLong("watermark"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("failed_count"),
                        resultSet.getObject("last_delivered_at", LocalDateTime.class) == null ? null
                                : resultSet.getObject("last_delivered_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
                ),
                teamId.toString(),
                seasonId.toString()
        );
    }

    @Override
    public Optional<ExecutionState> findExecutionState(GenerationTarget target) {
        return jdbcTemplate.query("""
                SELECT execution_status, lease_expires_at FROM brief_edition_generation_execution
                WHERE team_id = UUID_TO_BIN(?) AND season_id = UUID_TO_BIN(?)
                AND week_start = ? AND zone_id = ? AND delivery_watermark = ?
                """, (rs, row) -> new ExecutionState(rs.getString("execution_status"),
                        rs.getObject("lease_expires_at", LocalDateTime.class) == null ? null
                                : rs.getObject("lease_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                target.teamId().toString(), target.seasonId().toString(), target.weekStart(),
                target.zoneId().getId(), target.deliveryWatermark()).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(
            GenerationTarget target,
            boolean deliveryComplete,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        LocalDateTime now = utc(claimedAt);
        UUID proposedExecutionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO brief_edition_generation_execution (
                    execution_id,
                    team_id,
                    season_id,
                    week_start,
                    zone_id,
                    delivery_watermark,
                    execution_status,
                    attempt_count,
                    created_at,
                    updated_at
                ) VALUES (
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    'PENDING',
                    0,
                    ?,
                    ?
                )
                ON DUPLICATE KEY UPDATE execution_id = execution_id
                """,
                proposedExecutionId.toString(),
                target.teamId().toString(),
                target.seasonId().toString(),
                target.weekStart(),
                target.zoneId().getId(),
                target.deliveryWatermark(),
                now,
                now
        );

        StoredExecution execution = jdbcTemplate.queryForObject(
                """
                SELECT
                    BIN_TO_UUID(execution_id) AS execution_id,
                    execution_status,
                    lease_expires_at,
                    BIN_TO_UUID(edition_id) AS edition_id,
                    edition_generation,
                    source_cursor,
                    edition_etag,
                    created_new,
                    result_code
                FROM brief_edition_generation_execution
                WHERE team_id = UUID_TO_BIN(?)
                AND season_id = UUID_TO_BIN(?)
                AND week_start = ?
                AND zone_id = ?
                AND delivery_watermark = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new StoredExecution(
                        UUID.fromString(resultSet.getString("execution_id")),
                        resultSet.getString("execution_status"),
                        resultSet.getObject("lease_expires_at", LocalDateTime.class),
                        resultSet.getString("edition_id") == null
                                ? null
                                : UUID.fromString(resultSet.getString("edition_id")),
                        resultSet.getObject("edition_generation", Long.class),
                        resultSet.getObject("source_cursor", Long.class),
                        resultSet.getString("edition_etag"),
                        resultSet.getObject("created_new", Boolean.class),
                        resultSet.getString("result_code")
                ),
                target.teamId().toString(),
                target.seasonId().toString(),
                target.weekStart(),
                target.zoneId().getId(),
                target.deliveryWatermark()
        );

        if (!deliveryComplete) {
            return new ClaimResult.DeliveryIncomplete(execution.executionId());
        }
        if ("SUCCEEDED".equals(execution.status())) {
            return execution.completed();
        }
        if ("PERMANENT_FAILURE".equals(execution.status())) {
            return new ClaimResult.PermanentlyFailed(
                    execution.executionId(),
                    execution.resultCode()
            );
        }
        if ("PROCESSING".equals(execution.status())
                && execution.leaseExpiresAt().isAfter(now)) {
            return new ClaimResult.InProgress(execution.executionId());
        }

        UUID leaseToken = UUID.randomUUID();
        jdbcTemplate.update(
                """
                UPDATE brief_edition_generation_execution
                SET execution_status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    lease_token = UUID_TO_BIN(?),
                    lease_expires_at = ?,
                    result_code = NULL,
                    updated_at = ?
                WHERE execution_id = UUID_TO_BIN(?)
                """,
                leaseToken.toString(),
                utc(claimedAt.plus(leaseDuration)),
                now,
                execution.executionId().toString()
        );
        return new ClaimResult.Claimed(execution.executionId(), leaseToken);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSucceeded(
            UUID executionId,
            UUID leaseToken,
            Instant completedAt,
            UUID editionId,
            long generation,
            long sourceCursor,
            String etag,
            boolean created
    ) {
        return jdbcTemplate.update(
                """
                UPDATE brief_edition_generation_execution
                SET execution_status = 'SUCCEEDED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    edition_id = UUID_TO_BIN(?),
                    edition_generation = ?,
                    source_cursor = ?,
                    edition_etag = ?,
                    created_new = ?,
                    result_code = 'BRIEF_GENERATED',
                    updated_at = ?
                WHERE execution_id = UUID_TO_BIN(?)
                AND execution_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                editionId.toString(),
                generation,
                sourceCursor,
                etag,
                created,
                utc(completedAt),
                executionId.toString(),
                leaseToken.toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetryableFailure(
            UUID executionId,
            UUID leaseToken,
            Instant failedAt,
            String code
    ) {
        return markFailure(
                executionId,
                leaseToken,
                failedAt,
                code,
                "RETRYABLE_FAILURE"
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPermanentFailure(
            UUID executionId,
            UUID leaseToken,
            Instant failedAt,
            String code
    ) {
        return markFailure(
                executionId,
                leaseToken,
                failedAt,
                code,
                "PERMANENT_FAILURE"
        );
    }

    private boolean markFailure(
            UUID executionId,
            UUID leaseToken,
            Instant failedAt,
            String code,
            String status
    ) {
        return jdbcTemplate.update(
                """
                UPDATE brief_edition_generation_execution
                SET execution_status = ?,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    result_code = ?,
                    updated_at = ?
                WHERE execution_id = UUID_TO_BIN(?)
                AND execution_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                status,
                code,
                utc(failedAt),
                executionId.toString(),
                leaseToken.toString()
        ) == 1;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record StoredExecution(
            UUID executionId,
            String status,
            LocalDateTime leaseExpiresAt,
            UUID editionId,
            Long generation,
            Long sourceCursor,
            String etag,
            Boolean created,
            String resultCode
    ) {

        private ClaimResult.Completed completed() {
            return new ClaimResult.Completed(
                    executionId,
                    editionId,
                    generation,
                    sourceCursor,
                    etag,
                    created
            );
        }
    }
}
