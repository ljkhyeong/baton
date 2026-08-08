package com.personal.baton.application.relay.port.in;

public interface DispatchRelayOutboxUseCase {

    DispatchResult dispatchPending();

    record DispatchResult(int claimedCount, int publishedCount, int retryCount) {

        public boolean hasFailures() {
            return retryCount > 0;
        }
    }
}
