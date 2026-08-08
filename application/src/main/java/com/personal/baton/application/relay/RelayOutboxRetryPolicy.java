package com.personal.baton.application.relay;

import java.time.Duration;

final class RelayOutboxRetryPolicy {

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    Duration delayAfterAttempt(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("RELAY attemptCount는 1 이상이어야 합니다");
        }
        int exponent = Math.min(attemptCount - 1, 16);
        long seconds = Math.multiplyExact(INITIAL_DELAY.toSeconds(), 1L << exponent);
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
    }
}
