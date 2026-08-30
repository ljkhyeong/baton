package com.personal.baton.adapter.out.persistence.calendar;

import com.personal.baton.application.calendar.CalendarDelivery;
import com.personal.baton.application.calendar.CalendarDeliveryPayload;
import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
        return insert(snapshot, findLatestSnapshot(snapshot.sourceItemId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendIfChanged(CalendarSnapshotDraft snapshot) {
        Optional<StoredSnapshot> latest = findLatestSnapshot(snapshot.sourceItemId());
        if (latest.filter(stored -> stored.matches(snapshot)).isPresent()) {
            return false;
        }
        insert(snapshot, latest);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendSeasonMetadataIfChanged(UUID seasonId, String displayName, Instant occurredAt) {
        List<String> latest = jdbcTemplate.queryForList(
                """
                SELECT display_name FROM calendar_season_metadata_outbox
                WHERE season_id = UUID_TO_BIN(?) ORDER BY id DESC LIMIT 1 FOR UPDATE
                """,
                String.class,
                seasonId.toString()
        );
        if (!latest.isEmpty() && latest.getFirst().equals(displayName)) {
            return false;
        }
        jdbcTemplate.update(
                """
                INSERT INTO calendar_season_metadata_outbox
                    (season_id, display_name, occurred_at, available_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?)
                """,
                seasonId.toString(), displayName, utc(occurredAt), utc(occurredAt)
        );
        return true;
    }

    private int insert(CalendarSnapshotDraft snapshot, Optional<StoredSnapshot> latest) {
        LocalDateTime sourceUpdatedAt = monotonicSourceUpdatedAt(snapshot, latest);
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
    public List<CalendarDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration,
            boolean seasonMetadata
    ) {
        if (seasonMetadata) {
            return claimPending(
                    "calendar_season_metadata_outbox", "season_id",
                    (row, index) -> new CalendarSeasonMetadata(
                            uuid(row.getBytes("season_id")), row.getInt("id"), row.getString("display_name")
                    ),
                    batchSize, claimedAt, leaseDuration
            );
        }
        return claimPending(
                "calendar_snapshot_outbox", "source_item_id",
                (row, index) -> snapshot(row), batchSize, claimedAt, leaseDuration
        );
    }

    private List<CalendarDelivery> claimPending(
            String table, String sourceColumn, RowMapper<CalendarDeliveryPayload> payloadMapper,
            int batchSize, Instant claimedAt, Duration leaseDuration
    ) {
        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT candidate.* FROM %s candidate
                WHERE (
                    (candidate.delivery_status = 'PENDING' AND candidate.available_at <= ?)
                    OR (
                        candidate.delivery_status = 'PROCESSING'
                        AND candidate.lease_expires_at <= ?
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM %s predecessor
                    WHERE predecessor.%s = candidate.%s
                    AND predecessor.id < candidate.id
                    AND predecessor.delivery_status IN ('PENDING', 'PROCESSING')
                )
                ORDER BY candidate.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.formatted(table, table, sourceColumn, sourceColumn),
                (resultSet, rowNumber) -> new ClaimCandidate(
                        payloadMapper.mapRow(resultSet, rowNumber),
                        resultSet.getInt("attempt_count")
                ),
                utc(Objects.requireNonNull(claimedAt, "CAL claim 시각은 필수입니다")),
                utc(claimedAt),
                batchSize
        );

        LocalDateTime leaseExpiresAt = utc(claimedAt.plus(leaseDuration));
        List<CalendarDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE %s
                    SET delivery_status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        lease_token = UUID_TO_BIN(?),
                        lease_expires_at = ?,
                        completed_at = NULL,
                        result_code = NULL
                    WHERE id = ?
                    """.formatted(table),
                    leaseToken.toString(),
                    leaseExpiresAt,
                    candidate.payload().revision()
            );
            if (updated == 1) {
                deliveries.add(new CalendarDelivery(candidate.payload(), candidate.attemptCount() + 1, leaseToken));
            }
        }
        return List.copyOf(deliveries);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE %s
                SET delivery_status = 'DELIVERED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    result_code = ?,
                    last_error_code = NULL
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """.formatted(table(payload)),
                utc(deliveredAt),
                resultCode,
                payload.revision(),
                leaseToken.toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE %s
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
                """.formatted(table(payload)),
                utc(availableAt),
                errorCode,
                payload.revision(),
                leaseToken.toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE %s
                SET delivery_status = 'FAILED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    result_code = NULL,
                    last_error_code = ?
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """.formatted(table(payload)),
                utc(failedAt),
                errorCode,
                payload.revision(),
                leaseToken.toString()
        ) == 1;
    }

    private LocalDateTime monotonicSourceUpdatedAt(
            CalendarSnapshotDraft snapshot,
            Optional<StoredSnapshot> latest
    ) {
        LocalDateTime requested = utc(snapshot.sourceUpdatedAt());
        return latest
                .map(StoredSnapshot::sourceUpdatedAt)
                .filter(previous -> !requested.isAfter(previous))
                .map(previous -> previous.plusNanos(1_000))
                .orElse(requested);
    }

    private Optional<StoredSnapshot> findLatestSnapshot(UUID sourceItemId) {
        return jdbcTemplate.query(
                        """
                        SELECT
                            BIN_TO_UUID(season_id) AS season_id,
                            calendar_status,
                            summary,
                            description,
                            location,
                            time_type,
                            at_instant,
                            at_local,
                            zone_id,
                            start_date,
                            end_date,
                            source_updated_at
                        FROM calendar_snapshot_outbox
                        WHERE source_item_id = ?
                        ORDER BY id DESC
                        LIMIT 1
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) -> new StoredSnapshot(
                                UUID.fromString(resultSet.getString("season_id")),
                                CalendarSnapshot.Status.valueOf(
                                        resultSet.getString("calendar_status")
                                ),
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
                                resultSet.getObject("source_updated_at", LocalDateTime.class)
                        ),
                        uuidBytes(sourceItemId)
                ).stream()
                .findFirst();
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

    private String table(CalendarDeliveryPayload payload) {
        return switch (payload) {
            case CalendarSnapshot ignored -> "calendar_snapshot_outbox";
            case CalendarSeasonMetadata ignored -> "calendar_season_metadata_outbox";
        };
    }

    private CalendarSnapshot snapshot(ResultSet row) throws SQLException {
        return new CalendarSnapshot(
                uuid(row.getBytes("event_id")),
                row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                uuid(row.getBytes("source_item_id")),
                uuid(row.getBytes("season_id")),
                row.getInt("id"),
                CalendarSnapshot.Status.valueOf(row.getString("calendar_status")),
                row.getString("summary"), row.getString("description"), row.getString("location"),
                time(
                        row.getString("time_type"),
                        row.getObject("at_instant", LocalDateTime.class),
                        row.getObject("at_local", LocalDateTime.class), row.getString("zone_id"),
                        row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class)
                ),
                row.getObject("source_updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
        );
    }

    private UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private record ClaimCandidate(CalendarDeliveryPayload payload, int attemptCount) {
    }

    private record StoredSnapshot(
            UUID seasonId,
            CalendarSnapshot.Status status,
            String summary,
            String description,
            String location,
            CalendarSnapshot.Time time,
            LocalDateTime sourceUpdatedAt
    ) {

        private boolean matches(CalendarSnapshotDraft snapshot) {
            return seasonId.equals(snapshot.seasonId())
                    && status == snapshot.status()
                    && summary.equals(snapshot.summary())
                    && Objects.equals(description, snapshot.description())
                    && Objects.equals(location, snapshot.location())
                    && time.equals(snapshot.time());
        }
    }
}
