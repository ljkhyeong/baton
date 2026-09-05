package com.personal.baton.application.brief.port.in;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.BriefEditionDeliveryStatus;
import com.personal.baton.application.brief.BriefEditionHistory;
import com.personal.baton.application.brief.BriefEditionComparison;
import java.util.UUID;

public interface BriefEditionUseCase {

    record LatestEditionQuery(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            String workspaceAccessKey
    ) {
    }

    record GenerateEditionCommand(
            UUID accountId,
            UUID teamId,
            UUID seasonId,
            String workspaceAccessKey
    ) {
    }

    record LatestEditionResult(BriefEditionSnapshot edition, String etag) {
    }

    record GenerationResult(
            UUID executionId,
            long deliveryWatermark,
            UUID editionId,
            long generation,
            long sourceCursor,
            String etag,
            boolean created
    ) {
    }

    BriefEditionHistory findEditionHistory(LatestEditionQuery scope, BriefEditionHistory.Query query);

    LatestEditionResult findPreviousWeekEdition(LatestEditionQuery scope, UUID editionId);

    LatestEditionResult findEdition(LatestEditionQuery scope, UUID editionId);

    BriefEditionDeliveryStatus findEditionDeliveryStatus(LatestEditionQuery scope, UUID editionId);

    BriefEditionComparison compareEditions(LatestEditionQuery scope, UUID fromEditionId, UUID toEditionId);

    LatestEditionResult findLatestEdition(LatestEditionQuery query);

    GenerationResult generateEdition(GenerateEditionCommand command);
}

