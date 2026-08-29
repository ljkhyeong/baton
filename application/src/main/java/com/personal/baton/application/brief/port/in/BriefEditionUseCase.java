package com.personal.baton.application.brief.port.in;

import com.personal.baton.application.brief.BriefEditionSnapshot;
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

    LatestEditionResult findLatestEdition(LatestEditionQuery query);

    GenerationResult generateEdition(GenerateEditionCommand command);
}

