package com.personal.baton.adapter.out.persistence.calendar;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.CalendarSnapshotDelivery;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import java.nio.ByteBuffer;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCalendarOutboxAdapter implements CalendarOutboxPort {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insert;

    public JdbcCalendarOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("calendar_snapshot_outbox")
                .usingColumns(
                        "event_id",
                        "source_item_id",
                        "season_id",
                        "occurred_at",
                        "calendar_status",
                        "summary",
                        "description",
                        "location",
                        "time_type",
                        "at_instant",
                        "at_local",
                        "zone_id",
                        "start_date",
                        "end_date",
                        "source_updated_at",
                        "available_at"
                )
                .usingGeneratedKeyColumns("id");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int append(CalendarSnapshotDraft snapshot) {
        LocalDateTime sourceUpdatedAt = monotonicSourceUpdatedAt(snapshot);
        LocalDateTime occurredAt = utc(snapshot.occurredAt());
        if (occurredAt.isBefore(sourceUpdatedAt)) {
            occurredAt = sourceUpdatedAt;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("event_id", uuidBytes(snapshot.eventId()), Types.BINARY)
                .addValue("source_item_id", uuidBytes(snapshot.sourceItemId()), Types.BINARY)
                .addValue("season_id", uuidBytes(snapshot.seasonId()), Types.BINARY)
                .addValue("occurred_at", occurredAt, Types.TIMESTAMP)
                .addValue("calendar_status", snapshot.status().name())
                .addValue("summary", snapshot.summary())
                .addValue("description", snapshot.description(), Types.LONGVARCHAR)
                .addValue("location", snapshot.location(), Types.VARCHAR)
                .addValue("source_updated_at", sourceUpdatedAt, Types.TIMESTAMP)
                .addValue("available_at", occurredAt, Types.TIMESTAMP);
        addTime(parameters, snapshot.time());
        return insert.executeAndReturnKey(parameters).intValue();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CalendarSnapshotDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    candidate.id,
                    BIN_TO_UUID(candidate.event_id) AS event_id,
                    BIN_TO_UUID(candidate.source_item_id) AS source_item_id,
                    BIN_TO_UUID(candidate.season_id) AS season_id,
                    candidate.occurred_at,
                    candidate.calendar_status,
                    candidate.summary,
                    candidate.description,
                    candidate.location,
                    candidate.time_type,
                    candidate.at_instant,
                    candidate.at_local,
                    candidate.zone_id,
                    candidate.start_date,
                    candidate.end_date,
                    candidate.source_updated_at,
                    candidate.attempt_count
                FROM calendar_snapshot_outbox candidate
                WHERE (
                    (candidate.delivery_status = 'PENDING' AND candidate.available_at <= ?)
                    OR (
                        candidate.delivery_status = 'PROCESSING'
                        AND candidate.lease_expires_at <= ?
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM calendar_snapshot_outbox predecessor
                    WHERE predecessor.source_item_id = candidate.source_item_id
                    AND predecessor.id < candidate.id
                    AND predecessor.delivery_status IN ('PENDING', 'PROCESSING')
                )
                ORDER BY candidate.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getInt("id"),
                        UUID.fromString(resultSet.getString("event_id")),
                        UUID.fromString(resultSet.getString("source_item_id")),
                        UUID.fromString(resultSet.getString("season_id")),
                        resultSet.getObject("occurred_at", LocalDateTime.class),
                        CalendarSnapshot.Status.valueOf(resultSet.getString("calendar_status")),
                        resultSet.getString("summary"),
                        resultSet.getString("description"),
                        resultSet.getString("location"),
                        time(
                                resultSet.getString("time_type"),
                                resultSet.getObject("at_instant", LocalDateTime.class),
                                resultSet.getObject("at_local", LocalDateTime.class),
                                resultSet.getString("zone_id"),
                                resultSet.getObject("start_date", LocalDate.class),
                                resultSet.getObject("end_date", LocalDate.class)
                        ),
                        resultSet.getObject("source_updated_at", LocalDateTime.class),
                        resultSet.getInt("attempt_count")
                ),
                utc(Objects.requireNonNull(claimedAt, "CAL claim 시각은 필수입니다")),
                utc(claimedAt),
                batchSize
        );

        LocalDateTime leaseExpiresAt = utc(claimedAt.plus(leaseDuration));
        List<CalendarSnapshotDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE calendar_snapshot_outbox
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
                    candidate.revision()
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
            int revision,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE calendar_snapshot_outbox
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
                utc(deliveredAt),
                resultCode,
                revision,
                leaseToken.toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            int revision,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE calendar_snapshot_outbox
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
                utc(availableAt),
                errorCode,
                revision,
                leaseToken.toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            int revision,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE calendar_snapshot_outbox
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
                utc(failedAt),
                errorCode,
                revision,
                leaseToken.toString()
        ) == 1;
    }

    private LocalDateTime monotonicSourceUpdatedAt(CalendarSnapshotDraft snapshot) {
        LocalDateTime requested = utc(snapshot.sourceUpdatedAt());
        return jdbcTemplate.query(
                        """
                        SELECT source_updated_at
                        FROM calendar_snapshot_outbox
                        WHERE source_item_id = ?
                        ORDER BY id DESC
                        LIMIT 1
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) ->
                                resultSet.getObject("source_updated_at", LocalDateTime.class),
                        uuidBytes(snapshot.sourceItemId())
                ).stream()
                .findFirst()
                .filter(previous -> !requested.isAfter(previous))
                .map(previous -> previous.plusNanos(1_000))
                .orElse(requested);
    }

    private void addTime(MapSqlParameterSource parameters, CalendarSnapshot.Time time) {
        parameters
                .addValue("at_instant", null, Types.TIMESTAMP)
                .addValue("at_local", null, Types.TIMESTAMP)
                .addValue("zone_id", null, Types.VARCHAR)
                .addValue("start_date", null, Types.DATE)
                .addValue("end_date", null, Types.DATE);
        switch (time) {
            case CalendarSnapshot.UtcPoint point -> parameters
                    .addValue("time_type", "UTC_POINT")
                    .addValue("at_instant", utc(point.at()), Types.TIMESTAMP);
            case CalendarSnapshot.ZonedLocalPoint point -> parameters
                    .addValue("time_type", "ZONED_LOCAL_POINT")
                    .addValue("at_local", point.at(), Types.TIMESTAMP)
                    .addValue("zone_id", point.zoneId());
            case CalendarSnapshot.AllDay allDay -> parameters
                    .addValue("time_type", "ALL_DAY")
                    .addValue("start_date", allDay.startDate(), Types.DATE)
                    .addValue("end_date", allDay.endDate(), Types.DATE);
        }
    }

    private CalendarSnapshot.Time time(
            String type,
            LocalDateTime atInstant,
            LocalDateTime atLocal,
            String zoneId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return switch (type) {
            case "UTC_POINT" -> new CalendarSnapshot.UtcPoint(
                    atInstant.toInstant(ZoneOffset.UTC)
            );
            case "ZONED_LOCAL_POINT" -> new CalendarSnapshot.ZonedLocalPoint(atLocal, zoneId);
            case "ALL_DAY" -> new CalendarSnapshot.AllDay(startDate, endDate);
            default -> throw new IllegalStateException("지원하지 않는 CAL 시간 형태입니다: " + type);
        };
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ClaimCandidate(
            int revision,
            UUID eventId,
            UUID sourceItemId,
            UUID seasonId,
            LocalDateTime occurredAt,
            CalendarSnapshot.Status status,
            String summary,
            String description,
            String location,
            CalendarSnapshot.Time time,
            LocalDateTime sourceUpdatedAt,
            int attemptCount
    ) {

        private CalendarSnapshotDelivery toDelivery(UUID leaseToken) {
            return new CalendarSnapshotDelivery(
                    new CalendarSnapshot(
                            eventId,
                            occurredAt.toInstant(ZoneOffset.UTC),
                            sourceItemId,
                            seasonId,
                            revision,
                            status,
                            summary,
                            description,
                            location,
                            time,
                            sourceUpdatedAt.toInstant(ZoneOffset.UTC)
                    ),
                    attemptCount + 1,
                    leaseToken
            );
        }
    }
}
