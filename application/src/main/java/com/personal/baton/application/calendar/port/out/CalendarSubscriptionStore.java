package com.personal.baton.application.calendar.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarSubscriptionStore {
    record Owner(UUID accountId, UUID teamId, UUID seasonId) {}
    record Stored(Owner owner, UUID subscriptionId, boolean revoked, boolean revocationPending, Instant leaseUntil) {}
    record Claim(Stored subscription, UUID token) {}
    Optional<Stored> find(Owner owner);
    Claim claim(Owner owner, boolean create, boolean revoking, Instant now);
    boolean release(Claim claim, boolean revoked);
    void requestRevocation(Owner owner);
    void requestMemberRevocation(UUID teamId, UUID memberId);
    void requestUnauthorizedRevocations(UUID teamId);
    List<Owner> pendingRevocations(Instant now);
}
