package com.personal.baton.application.watch.port.in;

public interface DispatchWatchMonitorOutboxUseCase {

    DispatchResult dispatchPending();

    record DispatchResult(int claimedCount, int deliveredCount, int failedCount) {

        public boolean hasFailures() {
            return failedCount > 0;
        }
    }
}
