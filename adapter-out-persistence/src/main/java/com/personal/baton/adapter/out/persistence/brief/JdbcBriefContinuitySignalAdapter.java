package com.personal.baton.adapter.out.persistence.brief;

import com.personal.baton.application.brief.BriefContinuityEvent;
import com.personal.baton.application.brief.BriefContinuitySignalState;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcBriefContinuitySignalAdapter implements BriefContinuitySignalStorePort {

    private static final String SOURCE_REFERENCE_PREFIX = "baton-continuity:";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBriefContinuitySignalAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lockSeason(UUID teamId, UUID seasonId) {
        Objects.requireNonNull(teamId, "teamId는 필수입니다");
        Objects.requireNonNull(seasonId, "seasonId는 필수입니다");
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_scope (season_id, team_id)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?))
                ON DUPLICATE KEY UPDATE season_id = VALUES(season_id)
                """,
                seasonId.toString(),
                teamId.toString()
        );
        String storedTeamId = jdbcTemplate.queryForObject(
                """
                SELECT BIN_TO_UUID(team_id)
                FROM brief_continuity_scope
                WHERE season_id = UUID_TO_BIN(?)
                FOR UPDATE
                """,
                String.class,
                seasonId.toString()
        );
        if (!teamId.toString().equalsIgnoreCase(storedTeamId)) {
            throw new IllegalStateException("BRIEF 연속성 신호 시즌의 팀이 일치하지 않습니다");
        }
    }

    @Override
    public List<BriefContinuitySignalState> findBySeason(UUID teamId, UUID seasonId) {
        return jdbcTemplate.query(
                """
                SELECT
                    BIN_TO_UUID(signal_id) AS signal_id,
                    signal_type,
                    BIN_TO_UUID(subject_id) AS subject_id,
                    source_severity,
                    signal_state,
                    latest_revision
                FROM brief_continuity_signal
                WHERE team_id = UUID_TO_BIN(?)
                AND season_id = UUID_TO_BIN(?)
                ORDER BY signal_type, subject_id
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new BriefContinuitySignalState(
                        UUID.fromString(resultSet.getString("signal_id")),
                        ContinuitySignalType.valueOf(resultSet.getString("signal_type")),
                        UUID.fromString(resultSet.getString("subject_id")),
                        ContinuitySignalSeverity.valueOf(resultSet.getString("source_severity")),
                        BriefContinuityEvent.State.valueOf(resultSet.getString("signal_state")),
                        resultSet.getLong("latest_revision")
                ),
                teamId.toString(),
                seasonId.toString()
        );
    }

    @Override
    public void append(UUID subjectId, BriefContinuityEvent event) {
        Objects.requireNonNull(subjectId, "subjectId는 필수입니다");
        Objects.requireNonNull(event, "BRIEF 연속성 이벤트는 필수입니다");
        UUID signalId = signalId(event.sourceReference());
        if (event.aggregateRevision() == 1) {
            jdbcTemplate.update(
                    """
                    INSERT INTO brief_continuity_signal (
                        signal_id,
                        team_id,
                        season_id,
                        signal_type,
                        subject_id,
                        source_severity,
                        signal_state,
                        latest_revision,
                        occurred_at
                    ) VALUES (
                        UUID_TO_BIN(?),
                        UUID_TO_BIN(?),
                        UUID_TO_BIN(?),
                        ?,
                        UUID_TO_BIN(?),
                        ?,
                        ?,
                        ?,
                        ?
                    )
                    """,
                    signalId.toString(),
                    event.workspaceId().toString(),
                    event.seasonId().toString(),
                    event.eventType().name(),
                    subjectId.toString(),
                    event.sourceSeverity().name(),
                    event.state().name(),
                    event.aggregateRevision(),
                    utc(event.occurredAt())
            );
        } else {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE brief_continuity_signal
                    SET source_severity = ?,
                        signal_state = ?,
                        latest_revision = ?,
                        occurred_at = ?
                    WHERE signal_id = UUID_TO_BIN(?)
                    AND team_id = UUID_TO_BIN(?)
                    AND season_id = UUID_TO_BIN(?)
                    AND signal_type = ?
                    AND subject_id = UUID_TO_BIN(?)
                    AND latest_revision = ?
                    """,
                    event.sourceSeverity().name(),
                    event.state().name(),
                    event.aggregateRevision(),
                    utc(event.occurredAt()),
                    signalId.toString(),
                    event.workspaceId().toString(),
                    event.seasonId().toString(),
                    event.eventType().name(),
                    subjectId.toString(),
                    event.aggregateRevision() - 1
            );
            if (updated != 1) {
                throw new IllegalStateException("BRIEF 연속성 신호 리비전이 연속되지 않습니다");
            }
        }

        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id,
                    signal_id,
                    workspace_id,
                    season_id,
                    event_type,
                    event_version,
                    source_severity,
                    source_reference,
                    aggregate_revision,
                    occurred_at,
                    event_state
                ) VALUES (
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    UUID_TO_BIN(?),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                event.eventId().toString(),
                signalId.toString(),
                event.workspaceId().toString(),
                event.seasonId().toString(),
                event.eventType().name(),
                event.eventVersion(),
                event.sourceSeverity().name(),
                event.sourceReference(),
                event.aggregateRevision(),
                utc(event.occurredAt()),
                event.state().name()
        );
    }

    private static UUID signalId(String sourceReference) {
        if (!sourceReference.startsWith(SOURCE_REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("BRIEF sourceReference 형식이 올바르지 않습니다");
        }
        return UUID.fromString(sourceReference.substring(SOURCE_REFERENCE_PREFIX.length()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
