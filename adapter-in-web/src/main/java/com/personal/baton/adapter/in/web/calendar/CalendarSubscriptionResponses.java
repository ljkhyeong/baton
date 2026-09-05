package com.personal.baton.adapter.in.web.calendar;

import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import java.net.URI;
import java.util.List;
import java.util.UUID;

public final class CalendarSubscriptionResponses {
    private CalendarSubscriptionResponses() {}
    public record SubscriptionSummaryResponse(UUID subscriptionId, UUID teamId, UUID seasonId, String teamName,
            String seasonName, CalendarSubscriptionUseCase.ManagementStatus managementStatus) {}
    public record SubscriptionListResponse(UUID accountId, List<SubscriptionSummaryResponse> subscriptions, UUID nextAfterSeasonId) {}
    public record SubscriptionResponse(UUID subscriptionId, UUID seasonId, CalendarSubscriptionUseCase.Status status) {}
    public record CredentialResponse(UUID subscriptionId, UUID seasonId, URI feedUrl) {
        @Override
        public String toString() { return "CredentialResponse[subscriptionId=" + subscriptionId + ", feedUrl=[REDACTED]]"; }
    }
}
