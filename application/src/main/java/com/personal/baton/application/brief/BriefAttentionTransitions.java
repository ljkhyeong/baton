package com.personal.baton.application.brief;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BriefAttentionTransitions(List<Transition> transitions, Long nextBeforeAggregateRevision) {
    public record Transition(
            UUID eventId, Long aggregateRevision, BriefAttentionPage.Status state,
            Instant observedAt, Boolean detectedRevisionGap
    ) {
    }

    public record Query(
            BriefAttentionPage.EventType eventType, String sourceReference,
            Long beforeAggregateRevision, int limit
    ) {
    }
}
