package com.personal.baton.application.delivery;

import java.time.Duration;

public record RetryBackoff(Duration initialDelay, Duration maxDelay) {

    public Duration delayAfterAttempt(int attemptCount) {
        int exponent = Math.min(attemptCount - 1, 16);
        long seconds = Math.multiplyExact(initialDelay.toSeconds(), 1L << exponent);
        return Duration.ofSeconds(Math.min(seconds, maxDelay.toSeconds()));
    }
}
