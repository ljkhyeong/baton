package com.personal.baton.application.brief.port.in;

public interface DispatchBriefContinuityOutboxUseCase {

    DispatchResult dispatchPending();

    record DispatchResult(int claimedCount, int deliveredCount, int failedCount) {

        public boolean hasFailures() {
            return failedCount > 0;
        }
    }
}
