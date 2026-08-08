package com.personal.baton.application.identity.port.in;

public interface DispatchEmailVerificationOutboxUseCase {

    DispatchResult dispatchPending();

    record DispatchResult(
            int claimedCount,
            int deliveredCount,
            int supersededCount,
            int failedCount
    ) {

        public boolean hasFailures() {
            return failedCount > 0;
        }
    }
}
