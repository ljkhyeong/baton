package com.personal.baton.application.brief;

import java.time.Instant;
import java.util.List;

public record BriefAttentionPage(List<Item> items, Cursor nextCursor) {

    public enum Status { ACTIVE, RESOLVED }

    public enum Severity { HIGH, MEDIUM }

    public enum EventType {
        HANDOFF_BLOCKED, ROUTINE_MISSED, DECISION_FOLLOW_UP_OVERDUE,
        ROLE_UNASSIGNED, ROLE_SUCCESSOR_MISSING, ROLE_PREPARATION_INCOMPLETE,
        ROUTINE_REPEATEDLY_OVERDUE, HANDOFF_INCOMPLETE
    }

    public record Cursor(EventType eventType, String sourceReference) {
    }

    public record Item(
            EventType reasonCode,
            Severity severity,
            String sourceReference,
            Status status,
            Instant observedAt,
            Long aggregateRevision,
            Integer ruleVersion,
            Boolean revisionGap
    ) {
    }

    public record Filter(
            Status status, Severity severity, Boolean revisionGap,
            Cursor after, int limit
    ) {
    }
}
