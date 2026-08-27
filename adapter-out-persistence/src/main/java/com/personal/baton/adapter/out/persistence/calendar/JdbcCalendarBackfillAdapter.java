package com.personal.baton.adapter.out.persistence.calendar;

import com.personal.baton.application.calendar.CalendarBackfillCandidate;
import com.personal.baton.application.calendar.port.out.CalendarBackfillPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCalendarBackfillAdapter implements CalendarBackfillPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCalendarBackfillAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarBackfillCandidate> findCandidates(UUID afterRoundId, int limit) {
        String select = """
                SELECT
                    BIN_TO_UUID(round_record.id) AS round_id,
                    BIN_TO_UUID(round_record.season_id) AS season_id
                FROM season_rounds round_record
                WHERE (
                    round_record.meeting_date IS NOT NULL
                    OR round_record.scheduled_at IS NOT NULL
                )
                AND (
                    round_record.archived_at IS NULL
                    OR EXISTS (
                        SELECT 1
                        FROM calendar_snapshot_outbox snapshot
                        WHERE snapshot.source_item_id = round_record.id
                    )
                )
                """;
        if (afterRoundId == null) {
            return jdbcTemplate.query(
                    select + """
                ORDER BY round_record.id
                LIMIT ?
                """,
                    (resultSet, rowNumber) -> candidate(
                            resultSet.getString("round_id"),
                            resultSet.getString("season_id")
                    ),
                    limit
            );
        }
        return jdbcTemplate.query(
                select + """
                AND round_record.id > UUID_TO_BIN(?)
                ORDER BY round_record.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> candidate(
                        resultSet.getString("round_id"),
                        resultSet.getString("season_id")
                ),
                afterRoundId.toString(),
                limit
        );
    }

    private CalendarBackfillCandidate candidate(String roundId, String seasonId) {
        return new CalendarBackfillCandidate(
                UUID.fromString(roundId),
                UUID.fromString(seasonId)
        );
    }
}
