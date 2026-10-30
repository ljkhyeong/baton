package com.personal.baton.adapter.out.persistence.calendar;

import static com.personal.baton.adapter.out.persistence.JdbcTimestamps.instant;
import static com.personal.baton.adapter.out.persistence.JdbcTimestamps.utc;

import com.personal.baton.adapter.out.persistence.LeasedOutboxTable;
import com.personal.baton.application.calendar.CalendarDelivery;
import com.personal.baton.application.calendar.CalendarDeliveryPayload;
import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class JdbcCalendarOutboxAdapter implements CalendarOutboxPort, CalendarRecoveryStatePort {

    private static final String SNAPSHOT_TABLE = "calendar_snapshot_outbox";
    private static final String METADATA_TABLE = "calendar_season_metadata_outbox";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insert;
    private final LeasedOutboxTable snapshotOutbox;
    private final LeasedOutboxTable metadataOutbox;

    public JdbcCalendarOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotOutbox = new LeasedOutboxTable(jdbcTemplate, SNAPSHOT_TABLE);
        this.metadataOutbox = new LeasedOutboxTable(jdbcTemplate, METADATA_TABLE);
        this.insert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName(SNAPSHOT_TABLE)
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
                );
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
        // 호출 경로가 첫 일관 읽기 전에 시즌 행을 배타 잠금하거나 커밋 전의 새 시즌이므로 잠금 읽기가 필요 없다.
        List<String> latest = jdbcTemplate.queryForList(
                """
                SELECT display_name FROM calendar_season_metadata_outbox
                WHERE season_id = UUID_TO_BIN(?) ORDER BY id DESC LIMIT 1
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requeueLatestSeasonMetadata(UUID seasonId, Instant availableAt) {
        return jdbcTemplate.update(
                """
                UPDATE calendar_season_metadata_outbox delivery
                JOIN (
                    SELECT MAX(id) AS id FROM calendar_season_metadata_outbox
                    WHERE season_id = UUID_TO_BIN(?)
                ) latest ON latest.id = delivery.id
                SET delivery.delivery_status = 'PENDING', delivery.available_at = ?,
                    delivery.lease_token = NULL, delivery.lease_expires_at = NULL, delivery.completed_at = NULL,
                    delivery.result_code = NULL, delivery.last_error_code = NULL
                WHERE delivery.delivery_status <> 'FAILED'
                """,
                seasonId.toString(), utc(availableAt)
        ) == 1;
    }

    @Override
    @Transactional
    public int requeueLatestSnapshots(Instant availableAt) {
        return jdbcTemplate.update(
                """
                UPDATE calendar_snapshot_outbox delivery
                JOIN (
                    SELECT source_item_id, MAX(id) AS id
                    FROM calendar_snapshot_outbox
                    GROUP BY source_item_id
                ) latest ON latest.id = delivery.id
                SET delivery.delivery_status = 'PENDING', delivery.available_at = ?,
                    delivery.lease_token = NULL, delivery.lease_expires_at = NULL,
                    delivery.completed_at = NULL, delivery.result_code = NULL,
                    delivery.last_error_code = NULL
                WHERE delivery.delivery_status <> 'FAILED'
                """,
                utc(availableAt)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryState> loadReadyState() {
        List<UUID> seasonIds = jdbcTemplate.queryForList(
                        "SELECT BIN_TO_UUID(id) FROM seasons ORDER BY id",
                        String.class
                ).stream()
                .map(UUID::fromString)
                .toList();
        List<RecoverySnapshot> snapshots = jdbcTemplate.query(
                """
                SELECT delivery.*
                FROM calendar_snapshot_outbox delivery
                JOIN (
                    SELECT source_item_id, MAX(id) AS id
                    FROM calendar_snapshot_outbox
                    GROUP BY source_item_id
                ) latest ON latest.id = delivery.id
                ORDER BY delivery.season_id, delivery.source_item_id
                """,
                (row, index) -> new RecoverySnapshot(
                        snapshot(row),
                        row.getString("delivery_status")
                )
        );
        List<RecoveryMetadata> metadata = jdbcTemplate.query(
                """
                SELECT delivery.*
                FROM calendar_season_metadata_outbox delivery
                JOIN (
                    SELECT season_id, MAX(id) AS id
                    FROM calendar_season_metadata_outbox
                    GROUP BY season_id
                ) latest ON latest.id = delivery.id
                ORDER BY delivery.season_id
                """,
                (row, index) -> new RecoveryMetadata(metadata(row), row.getString("delivery_status"))
        );
        if (snapshots.stream().anyMatch(item -> !"DELIVERED".equals(item.deliveryStatus()))
                || metadata.stream().anyMatch(item -> !"DELIVERED".equals(item.deliveryStatus()))) {
            return Optional.empty();
        }

        Map<UUID, List<CalendarSnapshot>> snapshotsBySeason = new LinkedHashMap<>();
        for (RecoverySnapshot item : snapshots) {
            snapshotsBySeason.computeIfAbsent(
                    item.snapshot().seasonId(),
                    ignored -> new ArrayList<>()
            ).add(item.snapshot());
        }
        Map<UUID, CalendarSeasonMetadata> metadataBySeason = new LinkedHashMap<>();
        for (RecoveryMetadata item : metadata) {
            metadataBySeason.put(item.metadata().seasonId(), item.metadata());
        }
        if (!metadataBySeason.keySet().containsAll(seasonIds)) {
            return Optional.empty();
        }
        List<SeasonState> seasons = seasonIds.stream()
                .map(seasonId -> new SeasonState(
                        seasonId,
                        snapshotsBySeason.getOrDefault(seasonId, List.of()),
                        metadataBySeason.get(seasonId)
                ))
                .toList();
        return Optional.of(new RecoveryState(seasons));
    }

    private void insert(CalendarSnapshotDraft snapshot, Optional<StoredSnapshot> latest) {
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
        insert.execute(parameters);
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
                    METADATA_TABLE, metadataOutbox, "season_id",
                    (row, index) -> metadata(row), batchSize, claimedAt, leaseDuration
            );
        }
        return claimPending(
                SNAPSHOT_TABLE, snapshotOutbox, "source_item_id",
                (row, index) -> snapshot(row), batchSize, claimedAt, leaseDuration
        );
    }

    private List<CalendarDelivery> claimPending(
            String table, LeasedOutboxTable outbox, String sourceColumn,
            RowMapper<CalendarDeliveryPayload> payloadMapper,
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

        Instant leaseExpiresAt = claimedAt.plus(leaseDuration);
        List<CalendarDelivery> deliveries = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID leaseToken = UUID.randomUUID();
            if (outbox.lease(candidate.payload().revision(), leaseToken, leaseExpiresAt)) {
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
        return outbox(payload).markDelivered(payload.revision(), leaseToken, deliveredAt, resultCode);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    ) {
        return outbox(payload).markRetry(payload.revision(), leaseToken, availableAt, errorCode);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    ) {
        return outbox(payload).markFailed(payload.revision(), leaseToken, failedAt, errorCode);
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
        // 첫 스냅샷까지 잠금 읽기를 하면 빈 범위의 gap 잠금이 인덱스 틈을 공유하는 다른 원본의 첫 INSERT를 막는다.
        if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM calendar_snapshot_outbox WHERE source_item_id = ?)",
                Boolean.class,
                uuidBytes(sourceItemId)
        ))) {
            return Optional.empty();
        }
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
                                time(resultSet),
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

    private CalendarSnapshot.Time time(ResultSet row) throws SQLException {
        String type = row.getString("time_type");
        return switch (type) {
            case "UTC_POINT" -> new CalendarSnapshot.UtcPoint(instant(row, "at_instant"));
            case "ZONED_LOCAL_POINT" -> new CalendarSnapshot.ZonedLocalPoint(
                    row.getObject("at_local", LocalDateTime.class),
                    row.getString("zone_id")
            );
            case "ALL_DAY" -> new CalendarSnapshot.AllDay(
                    row.getObject("start_date", LocalDate.class),
                    row.getObject("end_date", LocalDate.class)
            );
            default -> throw new IllegalStateException("지원하지 않는 CAL 시간 형태입니다: " + type);
        };
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private LeasedOutboxTable outbox(CalendarDeliveryPayload payload) {
        return switch (payload) {
            case CalendarSnapshot ignored -> snapshotOutbox;
            case CalendarSeasonMetadata ignored -> metadataOutbox;
        };
    }

    private CalendarSnapshot snapshot(ResultSet row) throws SQLException {
        return new CalendarSnapshot(
                uuid(row.getBytes("event_id")),
                instant(row, "occurred_at"),
                uuid(row.getBytes("source_item_id")),
                uuid(row.getBytes("season_id")),
                row.getInt("id"),
                CalendarSnapshot.Status.valueOf(row.getString("calendar_status")),
                row.getString("summary"), row.getString("description"), row.getString("location"),
                time(row),
                instant(row, "source_updated_at")
        );
    }

    private CalendarSeasonMetadata metadata(ResultSet row) throws SQLException {
        return new CalendarSeasonMetadata(
                uuid(row.getBytes("season_id")),
                row.getInt("id"),
                row.getString("display_name")
        );
    }

    private UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private record ClaimCandidate(CalendarDeliveryPayload payload, int attemptCount) {
    }

    private record RecoverySnapshot(CalendarSnapshot snapshot, String deliveryStatus) {
    }

    private record RecoveryMetadata(CalendarSeasonMetadata metadata, String deliveryStatus) {
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
