package com.personal.baton.application.brief.port.in;

import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefWeeklyResolutions;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import java.util.UUID;

public interface BriefAttentionUseCase {

    record Scope(UUID accountId, UUID teamId, UUID seasonId, String workspaceAccessKey) {
    }

    BriefWeeklyResolutions summarizeWeeklyResolutions(Scope scope, BriefAttentionPage.Cursor after, int limit);

    BriefAttentionSummary summarizeAttention(Scope scope);

    BriefAttentionPage findAttentionItems(Scope scope, BriefAttentionPage.Filter filter);

    BriefAttentionTransitions findAttentionTransitions(Scope scope, BriefAttentionTransitions.Query query);
}
