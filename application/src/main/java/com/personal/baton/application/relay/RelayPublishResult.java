package com.personal.baton.application.relay;

import java.util.Objects;

public record RelayPublishResult(Outcome outcome, String code) {

    public RelayPublishResult {
        Objects.requireNonNull(outcome, "RELAY publish outcome은 필수입니다");
    }

    public static RelayPublishResult confirmed() {
        return new RelayPublishResult(Outcome.CONFIRMED, null);
    }

    public static RelayPublishResult retryable(String code) {
        return new RelayPublishResult(Outcome.RETRYABLE_FAILURE, code);
    }

    public enum Outcome {
        CONFIRMED,
        RETRYABLE_FAILURE
    }
}
