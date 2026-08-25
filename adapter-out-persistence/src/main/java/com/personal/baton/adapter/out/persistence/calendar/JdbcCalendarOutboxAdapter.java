package com.personal.baton.adapter.out.persistence.calendar;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import java.nio.ByteBuffer;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
