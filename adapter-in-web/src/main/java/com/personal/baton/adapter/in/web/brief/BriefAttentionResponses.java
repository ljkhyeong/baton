package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionPage.EventType;
import com.personal.baton.application.brief.BriefAttentionPage.Severity;
import com.personal.baton.application.brief.BriefAttentionPage.Status;
import com.personal.baton.application.brief.BriefAttentionSummary;
import java.time.Instant;
import java.util.List;

public final class BriefAttentionResponses {
    private BriefAttentionResponses() {
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
