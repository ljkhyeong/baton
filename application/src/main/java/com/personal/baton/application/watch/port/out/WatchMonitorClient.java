package com.personal.baton.application.watch.port.out;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import java.util.Objects;

public interface WatchMonitorClient {

    SynchronizationResult synchronize(WatchMonitorDelivery delivery);

    enum Outcome {
        DELIVERED,
        STALE,
        INVALID_TARGET,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record SynchronizationResult(Outcome outcome, String code) {

        public SynchronizationResult {
            Objects.requireNonNull(outcome, "WATCH synchronization outcome은 필수입니다");
        }

        public static SynchronizationResult delivered() {
            return new SynchronizationResult(Outcome.DELIVERED, null);
        }

        public static SynchronizationResult stale() {
            return new SynchronizationResult(Outcome.STALE, "STALE_SOURCE_REVISION");
        }

        public static SynchronizationResult invalidTarget() {
            return new SynchronizationResult(Outcome.INVALID_TARGET, "INVALID_TARGET_URL");
        }

        public static SynchronizationResult retryable(String code) {
            return new SynchronizationResult(Outcome.RETRYABLE_FAILURE, code);
        }

        public static SynchronizationResult permanentFailure(String code) {
            return new SynchronizationResult(Outcome.PERMANENT_FAILURE, code);
        }
    }
}
