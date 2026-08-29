package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.port.out.BriefEditionServiceClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public final class DisabledBriefEditionServiceClient
        implements BriefEditionServiceClient {

    public static final DisabledBriefEditionServiceClient INSTANCE =
            new DisabledBriefEditionServiceClient();

    private DisabledBriefEditionServiceClient() {
    }

    @Override
    public Result findLatestEdition(UUID workspaceId, UUID seasonId) {
        return disabled();
    }

    @Override
    public Result generateEdition(
            UUID workspaceId,
            UUID seasonId,
            LocalDate weekStart,
            ZoneId zoneId
    ) {
        return disabled();
    }

    private Result disabled() {
        return Result.failure(
                Outcome.RETRYABLE_FAILURE,
                "BRIEF_SERVICE_API_DISABLED"
        );
    }
}

