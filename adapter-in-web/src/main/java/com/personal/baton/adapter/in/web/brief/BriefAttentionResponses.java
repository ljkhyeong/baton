package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionPage.EventType;
import com.personal.baton.application.brief.BriefAttentionPage.Severity;
import com.personal.baton.application.brief.BriefAttentionPage.Status;
import com.personal.baton.application.brief.BriefWeeklyResolutions;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BriefAttentionResponses {
    private BriefAttentionResponses() {
    }

    public record ResolutionsResponse(
            java.time.LocalDate weekStart, java.time.ZoneId zoneId, Instant windowStart, Instant windowEnd,
            Instant evaluatedAt, long resolvedCount
    ) {
        public static ResolutionsResponse from(BriefWeeklyResolutions summary) {
            return new ResolutionsResponse(summary.weekStart(), summary.zoneId(), summary.windowStart(), summary.windowEnd(),
                    summary.evaluatedAt(), summary.resolvedCount());
        }
    }

    public record SummaryResponse(long highCount, long mediumCount, long revisionGapCount) {
        public static SummaryResponse from(BriefAttentionSummary summary) {
            return new SummaryResponse(summary.highCount(), summary.mediumCount(), summary.revisionGapCount());
        }
    }

    public record PageResponse(List<ItemResponse> items, CursorResponse nextCursor) {
        public static PageResponse from(BriefAttentionPage page) {
            var cursor = page.nextCursor();
            return new PageResponse(page.items().stream().map(ItemResponse::from).toList(),
                    cursor == null ? null : new CursorResponse(cursor.eventType(), cursor.sourceReference()));
        }
    }

    public record CursorResponse(EventType eventType, String sourceReference) {
    }

    public record TransitionsResponse(List<TransitionResponse> transitions, Long nextBeforeAggregateRevision) {
        public static TransitionsResponse from(BriefAttentionTransitions history) {
            return new TransitionsResponse(history.transitions().stream().map(TransitionResponse::from).toList(),
                    history.nextBeforeAggregateRevision());
        }
    }

    public record TransitionResponse(
            UUID eventId, long aggregateRevision, Status state, Instant observedAt, boolean detectedRevisionGap,
            BriefAttentionTransitions.SourceSeverity sourceSeverity
    ) {
        public static TransitionResponse from(BriefAttentionTransitions.Transition transition) {
            return new TransitionResponse(transition.eventId(), transition.aggregateRevision(), transition.state(),
                    transition.observedAt(), transition.detectedRevisionGap(), transition.sourceSeverity());
        }
    }

    public record ItemResponse(
            EventType reasonCode, Severity severity, String sourceReference, Status status,
            Instant observedAt, long aggregateRevision, int ruleVersion, boolean revisionGap
    ) {
        public static ItemResponse from(BriefAttentionPage.Item item) {
            return new ItemResponse(item.reasonCode(), item.severity(), item.sourceReference(),
                    item.status(), item.observedAt(), item.aggregateRevision(), item.ruleVersion(), item.revisionGap());
        }
    }
}
