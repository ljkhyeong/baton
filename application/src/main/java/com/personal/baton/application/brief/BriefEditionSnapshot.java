package com.personal.baton.application.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record BriefEditionSnapshot(
        UUID editionId,
        UUID workspaceId,
        UUID seasonId,
        long generation,
        LocalDate weekStart,
        ZoneId zoneId,
        Instant windowStart,
        Instant windowEnd,
        long sourceCursor,
        Instant generatedAt,
        int ruleVersion,
        List<Item> items
) {

    public record Item(
            String sourceReference,
            String reasonCode,
            String severity,
            String status,
            Instant observedAt,
            int ruleVersion,
            Long aggregateRevision,
            Boolean revisionGap
    ) {
    }
}

