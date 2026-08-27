package com.personal.baton.application.calendar.port.in;

public interface DispatchCalendarOutboxUseCase {

    DispatchResult dispatchPending();

    record DispatchResult(int claimedCount, int deliveredCount, int failedCount) {

        public boolean hasFailures() {
            return failedCount > 0;
        }
    }
}
