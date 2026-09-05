package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.port.out.BriefServiceClient;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefWeeklyResolutions;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.personal.baton.application.brief.BriefEditionHistory;
import com.personal.baton.application.brief.BriefEditionComparison;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public final class DisabledBriefServiceClient
        implements BriefServiceClient {

    public static final DisabledBriefServiceClient INSTANCE =
            new DisabledBriefServiceClient();

    private DisabledBriefServiceClient() {
    }

    @Override
    public BriefWeeklyResolutions summarizeWeeklyResolutions(UUID workspaceId, UUID seasonId, LocalDate weekStart, ZoneId zoneId,
                                                              BriefAttentionPage.Cursor after, int limit) {
        throw new BriefIntegrationUnavailableException();
    }

    @Override
    public Result findLatestEditionForWeek(UUID workspaceId, UUID seasonId, LocalDate weekStart, ZoneId zoneId) {
        return disabled();
    }

    @Override
    public BriefAttentionSummary summarizeAttention(UUID workspaceId, UUID seasonId) {
        throw new BriefIntegrationUnavailableException();
    }

    @Override
    public BriefAttentionPage findAttentionItems(
            UUID workspaceId, UUID seasonId, BriefAttentionPage.Filter filter
    ) {
        throw new BriefIntegrationUnavailableException();
    }

    @Override
    public Result findEdition(UUID editionId) { return disabled(); }

    @Override
    public BriefEditionHistory findEditionHistory(UUID workspaceId, UUID seasonId, BriefEditionHistory.Query query) {
        throw new BriefIntegrationUnavailableException();
    }

    @Override
    public BriefEditionComparison compareEditions(UUID fromEditionId, UUID toEditionId) {
        throw new BriefIntegrationUnavailableException();
    }

    @Override
    public Result findLatestEdition(UUID workspaceId, UUID seasonId) {
        return disabled();
    }

    @Override
    public BriefAttentionTransitions findAttentionTransitions(
            UUID workspaceId, UUID seasonId, BriefAttentionTransitions.Query query
    ) {
        throw new BriefIntegrationUnavailableException();
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
