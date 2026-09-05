package com.personal.baton.application.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public record BriefWeeklyResolutions(
        LocalDate weekStart, ZoneId zoneId, Instant windowStart, Instant windowEnd,
        Instant evaluatedAt, Long resolvedCount
) {
}
