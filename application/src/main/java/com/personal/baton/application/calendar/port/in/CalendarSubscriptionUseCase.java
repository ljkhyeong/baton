package com.personal.baton.application.calendar.port.in;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public interface CalendarSubscriptionUseCase {
    record Scope(UUID accountId, UUID teamId, UUID seasonId, String accessKey) {
        @Override public String toString() { return "Scope[accountId=" + accountId + ", teamId=" + teamId + ", seasonId=" + seasonId + ", accessKey=<redacted>]"; }
    }
    enum Status { NOT_CREATED, IN_PROGRESS, ACTIVE, REISSUE_REQUIRED, REVOKED, REVOCATION_PENDING }
    record Subscription(UUID subscriptionId, UUID seasonId, Status status) {}
    record Credential(UUID subscriptionId, UUID seasonId, URI feedUrl) {
        @Override public String toString() { return "Credential[subscriptionId=" + subscriptionId + ", seasonId=" + seasonId + ", feedUrl=<redacted>]"; }
    }
    enum ManagementStatus { CHECK_REQUIRED, IN_PROGRESS, REVOKED, REVOCATION_PENDING }
    record Summary(UUID subscriptionId, UUID teamId, UUID seasonId, String teamName, String seasonName,
                   ManagementStatus managementStatus) {}
    record SubscriptionPage(List<Summary> subscriptions, UUID nextAfterSeasonId) {}
    SubscriptionPage list(UUID accountId, UUID afterSeasonId);
    Subscription find(Scope scope);
    Credential create(Scope scope);
    Credential rotate(Scope scope);
    void revoke(Scope scope);
    void revokePending();
}
