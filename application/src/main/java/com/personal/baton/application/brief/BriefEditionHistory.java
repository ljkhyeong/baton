package com.personal.baton.application.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record BriefEditionHistory(List<Summary> editions, Long nextBeforeGeneration) {
    public record Query(Long beforeGeneration, int limit) { }

    public record Summary(UUID editionId, long generation, LocalDate weekStart, ZoneId zoneId,
                          Instant generatedAt, long sourceCursor, int ruleVersion, int itemCount) { }
}
