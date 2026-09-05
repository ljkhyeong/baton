package com.personal.baton.application.calendar.port.out;

import java.net.URI;
import java.util.UUID;

public interface CalendarSubscriptionClient {
    enum Outcome { SUCCESS, NOT_FOUND, ALREADY_EXISTS, UNAVAILABLE, INVALID_RESPONSE }
    record Credential(UUID subscriptionId, URI feedUrl) {
        @Override public String toString() { return "Credential[subscriptionId=" + subscriptionId + ", feedUrl=<redacted>]"; }
    }
    record RemoteStatus(UUID subscriptionId, UUID seasonId, boolean revoked, boolean generationMatches) {}
    record Result(Outcome outcome, Credential credential, RemoteStatus status) {
        public static Result of(Outcome outcome) { return new Result(outcome, null, null); }
    }
    Result create(UUID subscriptionId, UUID seasonId);
    Result findSubscription(UUID subscriptionId);
    Result rotate(UUID subscriptionId);
    Result revoke(UUID subscriptionId);
}
