package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.BriefEditionDeliveryStatus;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerationResult;
import com.personal.baton.application.brief.BriefEditionHistory;
import com.personal.baton.application.brief.BriefEditionComparison;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class BriefEditionResponses {

    private BriefEditionResponses() {
    }

    public record DeliveryStatusResponse(UUID editionId, BriefEditionDeliveryStatus.Status status, Instant checkedAt) { }

    public record BriefEditionResponse(
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
            List<BriefEditionItemResponse> items
    ) {

        public static BriefEditionResponse from(BriefEditionSnapshot edition) {
            return new BriefEditionResponse(
                    edition.editionId(),
                    edition.workspaceId(),
                    edition.seasonId(),
                    edition.generation(),
                    edition.weekStart(),
                    edition.zoneId(),
                    edition.windowStart(),
                    edition.windowEnd(),
                    edition.sourceCursor(),
                    edition.generatedAt(),
                    edition.ruleVersion(),
                    edition.items().stream()
                            .map(BriefEditionItemResponse::from)
                            .toList()
            );
        }
    }

    public record BriefEditionItemResponse(
            String sourceReference,
            String reasonCode,
            String severity,
            String status,
            Instant observedAt,
            int ruleVersion,
            Long aggregateRevision,
            Boolean revisionGap,
            BriefEditionSnapshot.Section section
    ) {

        private static BriefEditionItemResponse from(BriefEditionSnapshot.Item item) {
            return new BriefEditionItemResponse(
                    item.sourceReference(),
                    item.reasonCode(),
                    item.severity(),
                    item.status(),
                    item.observedAt(),
                    item.ruleVersion(),
                    item.aggregateRevision(),
                    item.revisionGap(),
                    item.section()
            );
        }
    }

    public record SummaryResponse(UUID editionId, long generation, LocalDate weekStart, ZoneId zoneId,
                                  Instant generatedAt, long sourceCursor, int ruleVersion, int itemCount) {
        public static SummaryResponse from(BriefEditionHistory.Summary summary) {
            return new SummaryResponse(summary.editionId(), summary.generation(), summary.weekStart(), summary.zoneId(),
                    summary.generatedAt(), summary.sourceCursor(), summary.ruleVersion(), summary.itemCount());
        }
    }

    public record HistoryResponse(List<SummaryResponse> editions, Long nextBeforeGeneration) {
        public static HistoryResponse from(BriefEditionHistory history) {
            return new HistoryResponse(history.editions().stream().map(SummaryResponse::from).toList(), history.nextBeforeGeneration());
        }
    }

    public record ChangeResponse(BriefEditionItemResponse before, BriefEditionItemResponse after) { }

    public record ComparisonResponse(SummaryResponse from, SummaryResponse to,
                                     List<BriefEditionItemResponse> added, List<BriefEditionItemResponse> removed,
                                     List<ChangeResponse> changed) {
        public static ComparisonResponse from(BriefEditionComparison comparison) {
            return new ComparisonResponse(SummaryResponse.from(comparison.from()), SummaryResponse.from(comparison.to()),
                    comparison.added().stream().map(BriefEditionItemResponse::from).toList(),
                    comparison.removed().stream().map(BriefEditionItemResponse::from).toList(),
                    comparison.changed().stream().map(change -> new ChangeResponse(
                            BriefEditionItemResponse.from(change.before()), BriefEditionItemResponse.from(change.after()))).toList());
        }
    }

    public record BriefEditionGenerationResponse(
            UUID executionId,
            long deliveryWatermark,
            UUID editionId,
            long generation,
            long sourceCursor,
            boolean created
    ) {

        public static BriefEditionGenerationResponse from(GenerationResult result) {
            return new BriefEditionGenerationResponse(
                    result.executionId(),
                    result.deliveryWatermark(),
                    result.editionId(),
                    result.generation(),
                    result.sourceCursor(),
                    result.created()
            );
        }
    }
}

