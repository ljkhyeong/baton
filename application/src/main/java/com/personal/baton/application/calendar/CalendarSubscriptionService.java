package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.CalendarSubscriptionException.Reason;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient.Outcome;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient.Result;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore.Claim;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore.Owner;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore.Stored;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import java.time.Clock;

public final class CalendarSubscriptionService implements CalendarSubscriptionUseCase {
    private final VerifyWorkspaceAccessUseCase access;
    private final ActiveAccountTeamMembershipVerifier memberships;
    private final CalendarSubscriptionStore store;
    private final CalendarSubscriptionClient client;
    private final Clock clock;
    private final boolean enabled;

    public CalendarSubscriptionService(VerifyWorkspaceAccessUseCase access, ActiveAccountTeamMembershipVerifier memberships,
            CalendarSubscriptionStore store, CalendarSubscriptionClient client, Clock clock, boolean enabled) {
        this.access = access;
        this.memberships = memberships;
        this.store = store;
        this.client = client;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public Subscription find(Scope scope) {
        authorize(scope, false);
        Stored stored = store.find(owner(scope)).orElse(null);
        if (stored == null) return new Subscription(null, scope.seasonId(), Status.NOT_CREATED);
        if (stored.revocationPending()) return status(stored, Status.REVOCATION_PENDING);
        if (stored.leaseUntil() != null && stored.leaseUntil().isAfter(clock.instant())) return status(stored, Status.IN_PROGRESS);
        if (stored.revoked()) return status(stored, Status.REVOKED);
        Result result = client.findSubscription(stored.subscriptionId());
        if (result.outcome() == Outcome.NOT_FOUND) return status(stored, Status.NOT_CREATED);
        requireSuccess(result);
        if (!scope.seasonId().equals(result.status().seasonId())) throw new CalendarSubscriptionException(Reason.INVALID_RESPONSE);
        return status(stored, result.status().revoked() ? Status.REVOKED
                : result.status().generationMatches() ? Status.ACTIVE : Status.REISSUE_REQUIRED);
    }

    @Override
    public Credential create(Scope scope) { return issue(scope, true); }

    @Override
    public Credential rotate(Scope scope) { return issue(scope, false); }

    private Credential issue(Scope scope, boolean create) {
        authorize(scope, true);
        Claim claim = store.claim(owner(scope), create, false, clock.instant());
        boolean released = false;
        try {
            Result result = create ? client.create(claim.subscription().subscriptionId(), scope.seasonId())
                    : client.rotate(claim.subscription().subscriptionId());
            if (result.outcome() == Outcome.ALREADY_EXISTS) throw new CalendarSubscriptionException(Reason.CREDENTIAL_REQUIRED);
            requireSuccess(result);
            try {
                // CAL 응답을 기다리는 동안 바뀐 시즌·접근 키·구성원 권한을 다시 확인한다.
                authorize(scope, true);
            } catch (RuntimeException exception) {
                store.requestRevocation(owner(scope));
                throw exception;
            }
            boolean completed = store.release(claim, false);
            released = true;
            if (!completed) throw new CalendarSubscriptionException(Reason.IN_PROGRESS);
            return new Credential(result.credential().subscriptionId(), scope.seasonId(), result.credential().feedUrl());
        } finally {
            if (!released) store.release(claim, claim.subscription().revoked());
        }
    }

    @Override
    public void revoke(Scope scope) {
        authorize(scope, false);
        Owner owner = owner(scope);
        if (store.find(owner).isEmpty()) return;
        store.requestRevocation(owner);
        revokeOwner(owner);
    }

    @Override
    public void revokePending() {
        for (Owner owner : store.pendingRevocations(clock.instant())) {
            store.requestRevocation(owner);
            try {
                revokeOwner(owner);
            } catch (CalendarSubscriptionException exception) {
                // 외부 실패와 진행 중 요청은 다음 주기에 같은 구독 ID로 다시 폐기한다.
            }
        }
    }

    private void revokeOwner(Owner owner) {
        Claim claim = store.claim(owner, false, true, clock.instant());
        boolean released = false;
        try {
            Result result = client.revoke(claim.subscription().subscriptionId());
            if (result.outcome() != Outcome.NOT_FOUND) requireSuccess(result);
            boolean completed = store.release(claim, true);
            released = true;
            if (!completed) throw new CalendarSubscriptionException(Reason.IN_PROGRESS);
        } finally {
            if (!released) store.release(claim, claim.subscription().revoked());
        }
    }

    private void authorize(Scope scope, boolean issuing) {
        if (issuing) access.verifyMutation(scope.teamId(), scope.seasonId(), scope.accessKey());
        else access.verifyRead(scope.teamId(), scope.seasonId(), scope.accessKey());
        if (!enabled) throw new CalendarSubscriptionException(Reason.DISABLED);
        if (issuing && !memberships.hasActiveMembership(scope.accountId(), scope.teamId())) {
            throw new CalendarSubscriptionException(Reason.ACCESS_DENIED);
        }
    }

    private void requireSuccess(Result result) {
        if (result.outcome() == Outcome.SUCCESS) return;
        throw new CalendarSubscriptionException(switch (result.outcome()) {
            case NOT_FOUND -> Reason.NOT_FOUND;
            case INVALID_RESPONSE -> Reason.INVALID_RESPONSE;
            default -> Reason.UNAVAILABLE;
        });
    }
    private Owner owner(Scope scope) { return new Owner(scope.accountId(), scope.teamId(), scope.seasonId()); }
    private Subscription status(Stored stored, Status status) { return new Subscription(stored.subscriptionId(), stored.owner().seasonId(), status); }
}
