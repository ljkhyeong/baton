package com.personal.baton.adapter.out.persistence.watch;

import com.personal.baton.application.watch.WatchHealthChangedEvent;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private static final byte PRESENT = 1;
    private static final byte ABSENT = 0;

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
        byte[] fingerprint = fingerprint(event);
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
                    payload_fingerprint,
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
                fingerprint,
                utc(persistedAcceptedAt)
        );

        StoredEnvelope stored = findForUpdate(event.eventId());
        WatchHealthEventInboxStatus status = MessageDigest.isEqual(
                fingerprint,
                stored.payloadFingerprint()
        ) && stored.matches(event, resourceId)
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
                    payload_fingerprint,
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
                        resultSet.getBytes("payload_fingerprint"),
                        resultSet.getObject("accepted_at", LocalDateTime.class)
                                .toInstant(ZoneOffset.UTC)
                ),
                eventId.toString()
        );
    }

    private static byte[] fingerprint(WatchHealthChangedEvent event) {
        MessageDigest digest = sha256();
        append(digest, uuidBytes(event.eventId()));
        append(digest, utf8(event.eventType()));
        append(digest, utf8(event.resourceReference()));
        append(digest, ByteBuffer.allocate(Long.BYTES)
                .putLong(event.sourceRevision())
                .array());
        append(digest, event.attemptId() == null ? null : uuidBytes(event.attemptId()));
        append(digest, utf8(event.previousHealth().name()));
        append(digest, utf8(event.currentHealth().name()));
        append(digest, ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(event.changedAt().getEpochSecond())
                .putInt(event.changedAt().getNano())
                .array());
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private static void append(MessageDigest digest, byte[] value) {
        if (value == null) {
            digest.update(ABSENT);
            digest.update(intBytes(0));
            return;
        }
        digest.update(PRESENT);
        digest.update(intBytes(value.length));
        digest.update(value);
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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
            byte[] payloadFingerprint,
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
