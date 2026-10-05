package com.personal.baton.adapter.out.persistence.watch;

import static com.personal.baton.adapter.out.persistence.JdbcTimestamps.utc;

import com.personal.baton.application.watch.WatchHealthChangedEvent;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWatchHealthEventInboxAdapter implements WatchHealthEventInboxPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWatchHealthEventInboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public WatchHealthEventInboxResult accept(
            WatchHealthChangedEvent event,
            Instant acceptedAt
    ) {
        Objects.requireNonNull(event, "WATCH health event는 필수입니다");
        Objects.requireNonNull(acceptedAt, "WATCH health event 접수 시각은 필수입니다");

        UUID resourceId = event.resourceId();
        Instant persistedAcceptedAt = acceptedAt.truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                INSERT INTO watch_health_event_inbox (
                    event_id,
                    resource_id,
                    event_type,
                    resource_reference,
                    source_revision,
                    attempt_id,
                    previous_health,
                    current_health,
                    changed_at,
                    changed_at_nano_remainder,
                    accepted_at
                ) VALUES (
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                ON DUPLICATE KEY UPDATE
                    event_id = watch_health_event_inbox.event_id
                """,
                event.eventId().toString(),
                resourceId.toString(),
                event.eventType(),
                event.resourceReference(),
                event.sourceRevision(),
                uuidString(event.attemptId()),
                event.previousHealth().name(),
                event.currentHealth().name(),
                utc(event.changedAt().truncatedTo(ChronoUnit.MICROS)),
                event.changedAt().getNano() % 1_000,
                utc(persistedAcceptedAt)
        );

        StoredEnvelope stored = findForUpdate(event.eventId());
        WatchHealthEventInboxStatus status = stored.matches(event, resourceId)
                ? WatchHealthEventInboxStatus.ACCEPTED
                : WatchHealthEventInboxStatus.CONFLICT;
        return new WatchHealthEventInboxResult(status, stored.acceptedAt());
    }

    private StoredEnvelope findForUpdate(UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    BIN_TO_UUID(event_id) AS event_id,
                    BIN_TO_UUID(resource_id) AS resource_id,
                    event_type,
                    resource_reference,
                    source_revision,
                    BIN_TO_UUID(attempt_id) AS attempt_id,
                    previous_health,
                    current_health,
                    changed_at,
                    changed_at_nano_remainder,
                    accepted_at
                FROM watch_health_event_inbox
                WHERE event_id = UUID_TO_BIN(?)
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new StoredEnvelope(
                        UUID.fromString(resultSet.getString("event_id")),
                        UUID.fromString(resultSet.getString("resource_id")),
                        resultSet.getString("event_type"),
                        resultSet.getString("resource_reference"),
                        resultSet.getLong("source_revision"),
                        uuid(resultSet.getString("attempt_id")),
                        WatchResourceHealth.valueOf(resultSet.getString("previous_health")),
                        WatchResourceHealth.valueOf(resultSet.getString("current_health")),
                        instant(
                                resultSet.getObject("changed_at", LocalDateTime.class),
                                resultSet.getInt("changed_at_nano_remainder")
                        ),
                        resultSet.getObject("accepted_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)
                ),
                eventId.toString()
        );
    }


    private static Instant instant(LocalDateTime micros, int nanoRemainder) {
        return micros.toInstant(ZoneOffset.UTC).plusNanos(nanoRemainder);
    }

    private static String uuidString(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record StoredEnvelope(
            UUID eventId,
            UUID resourceId,
            String eventType,
            String resourceReference,
            long sourceRevision,
            UUID attemptId,
            WatchResourceHealth previousHealth,
            WatchResourceHealth currentHealth,
            Instant changedAt,
            Instant acceptedAt
    ) {

        private boolean matches(WatchHealthChangedEvent event, UUID expectedResourceId) {
            return eventId.equals(event.eventId())
                    && resourceId.equals(expectedResourceId)
                    && eventType.equals(event.eventType())
                    && resourceReference.equals(event.resourceReference())
                    && sourceRevision == event.sourceRevision()
                    && Objects.equals(attemptId, event.attemptId())
                    && previousHealth == event.previousHealth()
                    && currentHealth == event.currentHealth()
                    && changedAt.equals(event.changedAt());
        }
    }
}
