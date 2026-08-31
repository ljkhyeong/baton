package com.personal.baton.application.brief.port.out;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public interface BriefServiceClient {

    BriefAttentionSummary summarizeAttention(UUID workspaceId, UUID seasonId);

    BriefAttentionTransitions findAttentionTransitions(
            UUID workspaceId, UUID seasonId, BriefAttentionTransitions.Query query
    );

    BriefAttentionPage findAttentionItems(
            UUID workspaceId, UUID seasonId, BriefAttentionPage.Filter filter
    );

    enum Outcome {
        COMPLETED,
        NOT_FOUND,
        INVALID_REQUEST,
        AUTHENTICATION_FAILURE,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record Result(
            Outcome outcome,
            BriefEditionSnapshot edition,
            String etag,
            boolean created,
            String code
    ) {

        public static Result completed(
                BriefEditionSnapshot edition,
                String etag,
                boolean created
        ) {
            return new Result(Outcome.COMPLETED, edition, etag, created, null);
        }

        public static Result failure(Outcome outcome, String code) {
            return new Result(outcome, null, null, false, code);
        }
    }

    Result findLatestEdition(UUID workspaceId, UUID seasonId);

    Result generateEdition(
            UUID workspaceId,
            UUID seasonId,
            LocalDate weekStart,
            ZoneId zoneId
    );
}
