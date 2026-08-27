package com.personal.baton.application.identity;

import java.time.Duration;

final class EmailVerificationRetryPolicy {

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofMinutes(30);

    Duration delayAfterAttempt(int attemptCount) {
        int exponent = Math.min(attemptCount - 1, 16);
        long seconds = Math.multiplyExact(INITIAL_DELAY.toSeconds(), 1L << exponent);
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
    }
}
