package com.personal.baton.application.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public record BriefWeeklyResolutions(
        LocalDate weekStart, ZoneId zoneId, Instant windowStart, Instant windowEnd,
        Instant evaluatedAt, Long resolvedCount, List<Item> items, BriefAttentionPage.Cursor nextCursor
) {
    public record Item(BriefAttentionPage.EventType reasonCode, String sourceReference,
                       Instant resolvedAt, Long resolvedRevision) {
    }
}
