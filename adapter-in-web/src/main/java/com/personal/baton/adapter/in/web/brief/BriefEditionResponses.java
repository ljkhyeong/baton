package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class BriefEditionResponses {

    private BriefEditionResponses() {
    }

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

