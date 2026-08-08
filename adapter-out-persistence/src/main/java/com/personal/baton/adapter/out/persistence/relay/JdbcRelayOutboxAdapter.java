package com.personal.baton.adapter.out.persistence.relay;

import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRelayOutboxAdapter implements RelayOutboxPort {

    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRelayOutboxAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(RelayOutboxEvent event) {
        Objects.requireNonNull(event, "RELAY outbox event는 필수입니다");
        LocalDateTime occurredAt = utc(event.occurredAt());
        jdbcTemplate.update(
                """
                INSERT INTO relay_event_outbox (
                    contract_version,
                    event_id,
                    event_type,
                    event_version,
                    subject_reference,
                    occurred_at,
                    available_at
                ) VALUES (
                    ?,
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                event.contractVersion(),
                event.eventId().toString(),
                event.eventType(),
                event.eventVersion(),
                event.subjectReference(),
                occurredAt,
                occurredAt
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RelayOutboxPublication> claim(Instant now, Duration leaseDuration) {
        Objects.requireNonNull(now, "RELAY outbox claim 시각은 필수입니다");
        Objects.requireNonNull(leaseDuration, "RELAY outbox lease 기간은 필수입니다");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("RELAY outbox lease 기간은 양수여야 합니다");
        }

        List<ClaimCandidate> candidates = jdbcTemplate.query(
                """
                SELECT
                    id,
                    contract_version,
                    BIN_TO_UUID(event_id) AS event_id,
                    event_type,
                    event_version,
                    subject_reference,
                    occurred_at,
                    attempt_count
                FROM relay_event_outbox
                WHERE (
                    publication_status = 'PENDING'
                    AND available_at <= ?
                ) OR (
                    publication_status = 'PUBLISHING'
                    AND lease_expires_at <= ?
                )
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getLong("id"),
                        new RelayOutboxEvent(
                                resultSet.getInt("contract_version"),
                                UUID.fromString(resultSet.getString("event_id")),
                                resultSet.getString("event_type"),
                                resultSet.getInt("event_version"),
                                resultSet.getString("subject_reference"),
                                resultSet.getObject("occurred_at", LocalDateTime.class)
                                        .toInstant(ZoneOffset.UTC)
                        ),
                        resultSet.getInt("attempt_count")
                ),
                utc(now),
                utc(now)
        );
        Optional<ClaimCandidate> candidate = candidates.stream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        UUID leaseToken = UUID.randomUUID();
        int updated = jdbcTemplate.update(
                """
                UPDATE relay_event_outbox
                SET publication_status = 'PUBLISHING',
                    attempt_count = attempt_count + 1,
                    lease_token = UUID_TO_BIN(?),
                    lease_expires_at = ?,
                    published_at = NULL
                WHERE id = ?
                """,
                leaseToken.toString(),
                utc(now.plus(leaseDuration)),
                candidate.get().rowId()
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(candidate.get().toPublication(leaseToken));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(RelayOutboxPublication publication, Instant publishedAt) {
        RelayOutboxPublication requiredPublication = requiredPublication(publication);
        return jdbcTemplate.update(
                """
                UPDATE relay_event_outbox
                SET publication_status = 'PUBLISHED',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    published_at = ?,
                    last_error_code = NULL
                WHERE id = ?
                AND publication_status = 'PUBLISHING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(publishedAt, "RELAY outbox 발행 완료 시각은 필수입니다")),
                requiredPublication.rowId(),
                requiredPublication.leaseToken().toString()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reschedule(
            RelayOutboxPublication publication,
            Instant availableAt,
            String errorCode
    ) {
        RelayOutboxPublication requiredPublication = requiredPublication(publication);
        return jdbcTemplate.update(
                """
                UPDATE relay_event_outbox
                SET publication_status = 'PENDING',
                    available_at = ?,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    published_at = NULL,
                    last_error_code = ?
                WHERE id = ?
                AND publication_status = 'PUBLISHING'
                AND lease_token = UUID_TO_BIN(?)
                """,
                utc(Objects.requireNonNull(availableAt, "RELAY outbox 재시도 가능 시각은 필수입니다")),
                boundedErrorCode(errorCode),
                requiredPublication.rowId(),
                requiredPublication.leaseToken().toString()
        ) == 1;
    }

    private static RelayOutboxPublication requiredPublication(
            RelayOutboxPublication publication
    ) {
        RelayOutboxPublication required = Objects.requireNonNull(
                publication,
                "RELAY outbox publication은 필수입니다"
        );
        Objects.requireNonNull(required.leaseToken(), "RELAY outbox lease token은 필수입니다");
        return required;
    }

    private static String boundedErrorCode(String errorCode) {
        if (errorCode != null && errorCode.length() > MAX_ERROR_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "RELAY outbox 오류 코드는 " + MAX_ERROR_CODE_LENGTH + "자 이하여야 합니다"
            );
        }
        return errorCode;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(
                Objects.requireNonNull(instant, "RELAY outbox 시각은 필수입니다")
                        .truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC
        );
    }

    private record ClaimCandidate(
            long rowId,
            RelayOutboxEvent event,
            int attemptCount
    ) {
        private RelayOutboxPublication toPublication(UUID leaseToken) {
            return new RelayOutboxPublication(rowId, event, attemptCount + 1, leaseToken);
        }
    }
}
